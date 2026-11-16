"""Business logic behind the mobile REST API.

The HTTP controller is a thin wrapper around this abstract model so the whole
protocol can be unit-tested without HTTP.

Protocol summary (see docs/ARCHITECTURE.md):
  * login(code, pin, device_id)     -> token + rep profile
  * pull(rep, since)                -> master data (products, prices, customers, stock...)
  * push(rep, ops)                  -> apply offline operations, idempotent by op uuid
"""
import json
import logging
from datetime import datetime, timedelta, timezone

from odoo import Command, api, fields, models, _
from odoo.exceptions import UserError, ValidationError

_logger = logging.getLogger(__name__)

API_VERSION = 1


def parse_dt(value):
    """ISO-8601 from the phone (UTC, 'Z' or offset) -> naive UTC datetime for Odoo."""
    if not value:
        return fields.Datetime.now()
    if isinstance(value, (int, float)):  # epoch millis
        return datetime.fromtimestamp(value / 1000.0, tz=timezone.utc).replace(tzinfo=None)
    text = str(value).strip().replace('Z', '+00:00')
    dt = datetime.fromisoformat(text)
    if dt.tzinfo:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt


def to_iso(dt):
    return dt.replace(tzinfo=timezone.utc).isoformat().replace('+00:00', 'Z') if dt else None


class SalesRepMobileApi(models.AbstractModel):
    _name = 'sales.rep.mobile.api'
    _description = 'Sales Rep Mobile API'

    # ------------------------------------------------------------------
    # Environment
    # ------------------------------------------------------------------
    @api.model
    def _api_env(self, rep):
        """Environment the API works in: the configured integration user,
        sudo, restricted to the rep's company."""
        user = rep.company_id.rep_api_user_id
        env = self.env(user=user.id) if user else self.env
        return env(su=True, context=dict(env.context, allowed_company_ids=[rep.company_id.id]))

    # ------------------------------------------------------------------
    # Login
    # ------------------------------------------------------------------
    @api.model
    def login(self, code, pin, device_id=None):
        res = self.env['sales.rep']._authenticate_pin(code, pin, device_id)
        if not res:
            raise UserError(_('Wrong code or PIN.'))
        rep, token = res
        return {'api_version': API_VERSION, 'token': token, **self._profile(rep)}

    @api.model
    def _profile(self, rep):
        company = rep.company_id
        currency = company.currency_id
        return {
            'rep': {
                'id': rep.id,
                'name': rep.name,
                'code': rep.code,
                'phone': rep.phone or '',
                'invoice_prefix': rep.code,
                'last_van_sale_seq': rep.last_van_sale_seq,
                'can_create_customers': rep.can_create_customers,
            },
            'company': {
                'name': company.name,
                'phone': company.phone or '',
                'address': ', '.join(filter(None, [company.street, company.city])),
                'vat': company.vat or '',
            },
            'currency': {
                'name': currency.name,
                'symbol': currency.symbol,
                'decimals': currency.decimal_places,
            },
            'config': {
                'geofence_radius': company.rep_geofence_radius or 50,
                'geofence_exit_buffer': company.rep_geofence_exit_buffer or 0,
                'gps_max_accuracy': company.rep_gps_max_accuracy or 40,
                'allow_manual_visit': company.rep_allow_manual_visit,
            },
        }

    # ------------------------------------------------------------------
    # Pull
    # ------------------------------------------------------------------
    @api.model
    def pull(self, rep, since=None):
        env = self._api_env(rep)
        rep = rep.with_env(env)
        # 2-minute overlap: records committed while the previous pull ran are not missed.
        since_dt = parse_dt(since) - timedelta(minutes=2) if since else None
        server_time = fields.Datetime.now()

        Product = env['product.product']
        product_domain = [('sale_ok', '=', True), ('company_id', 'in', [False, rep.company_id.id])]
        all_products = Product.search(product_domain)
        changed_products = all_products.filtered(
            lambda p: not since_dt or max(p.write_date, p.product_tmpl_id.write_date) > since_dt)

        Partner = env['res.partner']
        customer_domain = [('sales_rep_id', '=', rep.id), ('active', '=', True)]
        all_customers = Partner.search(customer_domain)
        changed_customers = all_customers if not since_dt else all_customers.filtered(
            lambda c: c.write_date > since_dt)

        pricelists = (all_customers.mapped('property_product_pricelist')
                      | rep.default_pricelist_id | rep.load_pricelist_id)
        prices = {}
        for pricelist in pricelists:
            prices[str(pricelist.id)] = {
                str(pid): price for pid, price in self._pricelist_prices(pricelist, all_products, rep).items()}

        load_prices = (self._pricelist_prices(rep.load_pricelist_id, all_products, rep)
                       if rep.load_pricelist_id else {})
        van_stock = rep._van_stock()
        main_stock = rep._main_stock()
        dues = {c.id: c.rep_customer_due for c in all_customers}

        return {
            'api_version': API_VERSION,
            'server_time': to_iso(server_time),
            'full': not since_dt,
            **self._profile(rep),
            'rep_balance': rep.balance,
            'default_pricelist_id': rep.default_pricelist_id.id or None,
            'products': [self._product_json(p, load_prices) for p in changed_products],
            'product_ids': all_products.ids,
            'customers': [self._customer_json(c, dues) for c in changed_customers],
            'customer_ids': all_customers.ids,
            'customer_dues': {str(k): v for k, v in dues.items()},
            'prices': prices,
            'van_stock': {str(k): v for k, v in van_stock.items() if v},
            'main_stock': {str(k): v for k, v in main_stock.items() if v > 0},
            'loads': [self._load_json(l) for l in env['sales.rep.load'].search(
                [('rep_id', '=', rep.id)], limit=30)],
        }

    @api.model
    def _pricelist_prices(self, pricelist, products, rep):
        if not pricelist or not products:
            return {}
        try:
            return pricelist._get_products_price(products, 1.0)
        except (AttributeError, TypeError):  # older signature
            return {p.id: pricelist._get_product_price(p, 1.0) for p in products}

    @api.model
    def _product_json(self, product, load_prices):
        return {
            'id': product.id,
            'name': product.display_name,
            'default_code': product.default_code or '',
            'barcode': product.barcode or '',
            'uom': product.uom_id.name,
            'category': product.categ_id.name or '',
            'list_price': product.lst_price,
            'load_price': load_prices.get(product.id, product.lst_price),
            'write_date': to_iso(product.write_date),
        }

    @api.model
    def _customer_json(self, partner, dues):
        return {
            'id': partner.id,
            'uuid': partner.mobile_uuid or None,
            'name': partner.name,
            'phone': partner.phone or '',
            'street': partner.street or '',
            'city': partner.city or '',
            'latitude': partner.partner_latitude or None,
            'longitude': partner.partner_longitude or None,
            'pricelist_id': partner.property_product_pricelist.id or None,
            'due': dues.get(partner.id, 0.0),
        }

    @api.model
    def _load_json(self, load):
        return {
            'id': load.id,
            'uuid': load.mobile_uuid or None,
            'name': load.name,
            'type': load.load_type,
            'state': load.state,
            'date': to_iso(load.date),
            'amount_total': load.amount_total,
            'invoice': load.invoice_id.name or None,
            'lines': [{'product_id': l.product_id.id, 'product': l.product_id.display_name,
                       'qty': l.qty, 'price_unit': l.price_unit} for l in load.line_ids],
        }

    # ------------------------------------------------------------------
    # Push
    # ------------------------------------------------------------------
    @api.model
    def push(self, rep, ops):
        env = self._api_env(rep)
        rep = rep.with_env(env)
        Log = env['sales.rep.sync.log']
        results = []
        for op in ops or []:
            uuid = op.get('uuid')
            op_type = op.get('type')
            payload = op.get('payload') or {}
            if not uuid or not op_type:
                results.append({'uuid': uuid, 'status': 'error', 'message': 'uuid and type are required'})
                continue
            done = Log.search([('uuid', '=', uuid), ('state', '=', 'ok')], limit=1)
            if done:
                results.append({'uuid': uuid, 'status': 'ok', 'duplicate': True,
                                'res_model': done.res_model, 'res_id': done.res_id})
                continue
            handler = getattr(self, f'_op_{op_type}', None)
            try:
                if not handler:
                    raise ValidationError(_('Unknown operation type: %s', op_type))
                with env.cr.savepoint():
                    record = handler(rep, uuid, payload)
                    Log.create({
                        'rep_id': rep.id, 'uuid': uuid, 'op_type': op_type, 'state': 'ok',
                        'res_model': record._name if record else False,
                        'res_id': record.id if record else 0,
                        'payload': json.dumps(payload, ensure_ascii=False),
                        'client_date': op.get('created_at'),
                    })
                results.append({'uuid': uuid, 'status': 'ok',
                                'res_model': record._name if record else None,
                                'res_id': record.id if record else None,
                                'name': record.display_name if record else None})
            except (UserError, ValidationError) as e:
                msg = e.args[0] if e.args else str(e)
                results.append({'uuid': uuid, 'status': 'error', 'message': msg})
                self._log_error(rep, uuid, op_type, payload, msg)
            except Exception as e:  # noqa: BLE001 - one bad op must not block the others
                _logger.exception('Mobile op %s (%s) failed', uuid, op_type)
                results.append({'uuid': uuid, 'status': 'error', 'message': str(e)})
                self._log_error(rep, uuid, op_type, payload, str(e))
        rep.sudo().last_sync = fields.Datetime.now()
        return {'results': results, 'server_time': to_iso(fields.Datetime.now())}

    @api.model
    def _log_error(self, rep, uuid, op_type, payload, message):
        self.env['sales.rep.sync.log'].sudo().create({
            'rep_id': rep.id, 'uuid': uuid, 'op_type': op_type, 'state': 'error',
            'message': message, 'payload': json.dumps(payload, ensure_ascii=False),
        })

    # -- resolvers ------------------------------------------------------
    @api.model
    def _customer(self, rep, payload, required=True):
        Partner = rep.env['res.partner']
        partner = Partner.browse()
        if payload.get('customer_id'):
            partner = Partner.browse(int(payload['customer_id'])).exists()
        elif payload.get('customer_uuid'):
            partner = Partner.search([('mobile_uuid', '=', payload['customer_uuid'])], limit=1)
        if not partner:
            if required:
                raise ValidationError(_('Customer not found (it may not be synced yet).'))
            return partner
        if partner.sales_rep_id != rep:
            raise ValidationError(_('Customer %s is not assigned to this rep.', partner.name))
        return partner

    @api.model
    def _visit(self, rep, visit_uuid):
        if not visit_uuid:
            return rep.env['sales.rep.visit']
        return rep.env['sales.rep.visit'].search(
            [('mobile_uuid', '=', visit_uuid), ('rep_id', '=', rep.id)], limit=1)

    @api.model
    def _product(self, rep, product_id):
        product = rep.env['product.product'].browse(int(product_id)).exists()
        if not product or not product.sale_ok:
            raise ValidationError(_('Product %s is not available any more.', product_id))
        return product

    # -- operations -----------------------------------------------------
    @api.model
    def _op_customer_upsert(self, rep, uuid, p):
        Partner = rep.env['res.partner']
        vals = {k: p[k] for k in ('name', 'phone', 'street', 'city') if p.get(k) is not None}
        if p.get('latitude') is not None and p.get('longitude') is not None:
            vals.update(partner_latitude=p['latitude'], partner_longitude=p['longitude'])
        partner = self._customer(rep, p, required=False)
        if partner:
            partner.write(vals)
            return partner
        if not rep.can_create_customers:
            raise ValidationError(_('This rep is not allowed to create customers.'))
        if not vals.get('name'):
            raise ValidationError(_('Customer name is required.'))
        vals.update({
            'mobile_uuid': p.get('customer_uuid') or uuid,
            'sales_rep_id': rep.id,
            'company_id': rep.company_id.id,
            'customer_rank': 1,
        })
        if rep.default_pricelist_id:
            vals['property_product_pricelist'] = rep.default_pricelist_id.id
        return Partner.create(vals)

    @api.model
    def _op_customer_location(self, rep, uuid, p):
        partner = self._customer(rep, p)
        partner.write({'partner_latitude': p['latitude'], 'partner_longitude': p['longitude']})
        return partner

    @api.model
    def _op_visit_open(self, rep, uuid, p):
        Visit = rep.env['sales.rep.visit']
        visit_uuid = p.get('visit_uuid') or uuid
        existing = Visit.search([('mobile_uuid', '=', visit_uuid)], limit=1)
        if existing:
            return existing
        partner = self._customer(rep, p)
        return Visit.create({
            'mobile_uuid': visit_uuid,
            'rep_id': rep.id,
            'partner_id': partner.id,
            'check_in': parse_dt(p.get('check_in')),
            'in_latitude': p.get('latitude') or 0.0,
            'in_longitude': p.get('longitude') or 0.0,
            'in_distance': p.get('distance') or 0.0,
            'open_mode': p.get('mode') or 'auto',
        })

    @api.model
    def _op_visit_close(self, rep, uuid, p):
        visit = self._visit(rep, p.get('visit_uuid'))
        if not visit:
            raise ValidationError(_('Visit not found (it may not be synced yet).'))
        visit.write({
            'state': 'closed',
            'check_out': parse_dt(p.get('check_out')),
            'out_latitude': p.get('latitude') or 0.0,
            'out_longitude': p.get('longitude') or 0.0,
            'out_distance': p.get('distance') or 0.0,
            'close_mode': p.get('mode') or 'auto',
            'no_sale_reason': p.get('no_sale_reason') or False,
            'note': p.get('note') or visit.note,
        })
        return visit

    @api.model
    def _op_van_sale(self, rep, uuid, p):
        partner = self._customer(rep, p)
        lines = []
        for line in p.get('lines') or []:
            qty = float(line.get('qty') or 0)
            if qty <= 0:
                continue
            product = self._product(rep, line['product_id'])
            lines.append(Command.create({
                'product_id': product.id,
                'qty': qty,
                'price_unit': float(line.get('price_unit') or 0.0),
                'discount': float(line.get('discount') or 0.0),
            }))
        if not lines:
            raise ValidationError(_('A van sale needs at least one line.'))
        sale = rep.env['sales.rep.van.sale'].create({
            'name': p.get('number') or uuid,
            'mobile_uuid': uuid,
            'rep_id': rep.id,
            'partner_id': partner.id,
            'visit_id': self._visit(rep, p.get('visit_uuid')).id,
            'date': parse_dt(p.get('date')),
            'sale_type': p.get('sale_type') or 'sale',
            'amount_discount': float(p.get('discount_amount') or 0.0),
            'amount_paid': float(p.get('paid') or 0.0),
            'latitude': p.get('latitude') or 0.0,
            'longitude': p.get('longitude') or 0.0,
            'note': p.get('note') or False,
            'printed_count': int(p.get('printed_count') or 0),
            'line_ids': lines,
        })
        sale._do_stock()
        seq = int(p.get('seq') or 0)
        if seq > rep.last_van_sale_seq:
            rep.sudo().last_van_sale_seq = seq
        return sale

    @api.model
    def _op_sale_order(self, rep, uuid, p):
        partner = self._customer(rep, p)
        SaleOrder = rep.env['sale.order']
        vals = {
            'partner_id': partner.id,
            'company_id': rep.company_id.id,
            'warehouse_id': rep.warehouse_id.id,
            'sales_rep_id': rep.id,
            'rep_visit_id': self._visit(rep, p.get('visit_uuid')).id,
            'mobile_uuid': uuid,
            'origin': _('Rep %s', rep.code),
            'note': p.get('note') or False,
            'date_order': parse_dt(p.get('date')),
            'order_line': [],
        }
        pricelist = rep._customer_pricelist(partner)
        if pricelist:
            vals['pricelist_id'] = pricelist.id
        for line in p.get('lines') or []:
            qty = float(line.get('qty') or 0)
            if qty <= 0:
                continue
            product = self._product(rep, line['product_id'])
            vals['order_line'].append(Command.create({'product_id': product.id, 'product_uom_qty': qty}))
        if not vals['order_line']:
            raise ValidationError(_('An order needs at least one line.'))
        if p.get('delivery_date'):
            vals['commitment_date'] = parse_dt(p['delivery_date'])
        order = SaleOrder.create(vals)
        if rep.company_id.rep_auto_confirm_orders:
            order.action_confirm()
        return order

    @api.model
    def _op_collection(self, rep, uuid, p):
        partner = self._customer(rep, p)
        amount = float(p.get('amount') or 0.0)
        if amount <= 0:
            raise ValidationError(_('The amount must be positive.'))
        return rep.env['sales.rep.collection'].create({
            'name': p.get('number') or uuid,
            'mobile_uuid': uuid,
            'rep_id': rep.id,
            'partner_id': partner.id,
            'visit_id': self._visit(rep, p.get('visit_uuid')).id,
            'date': parse_dt(p.get('date')),
            'amount': amount,
            'note': p.get('note') or False,
        })

    @api.model
    def _op_load_request(self, rep, uuid, p):
        lines = []
        for line in p.get('lines') or []:
            qty = float(line.get('qty') or 0)
            if qty > 0:
                lines.append(Command.create({'product_id': self._product(rep, line['product_id']).id,
                                             'qty': qty}))
        if not lines:
            raise ValidationError(_('A loading request needs at least one line.'))
        load = rep.env['sales.rep.load'].create({
            'rep_id': rep.id,
            'mobile_uuid': uuid,
            'load_type': p.get('type') if p.get('type') in ('load', 'unload') else 'load',
            'note': p.get('note') or False,
            'state': 'requested',
            'line_ids': lines,
        })
        load.action_fill_prices()
        return load
