import hashlib
import hmac
import secrets

from odoo import api, fields, models, _
from odoo.exceptions import UserError, ValidationError

PBKDF2_ROUNDS = 120_000


def _hash_pin(pin, salt=None):
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac('sha256', pin.encode(), salt.encode(), PBKDF2_ROUNDS).hex()
    return f'{salt}${digest}'


def _check_pin(pin, stored):
    if not stored or '$' not in stored:
        return False
    salt, _digest = stored.split('$', 1)
    return hmac.compare_digest(_hash_pin(pin, salt), stored)


def hash_token(token):
    return hashlib.sha256(token.encode()).hexdigest()


class SalesRep(models.Model):
    """A van sales rep.

    A rep is *not* an Odoo user (no licence). He is represented by a
    res.partner (so the company can invoice him like a customer when he loads
    goods) and owns a dedicated internal stock location: his van.
    """
    _name = 'sales.rep'
    _description = 'Van Sales Rep'
    _inherit = ['mail.thread']
    _order = 'name'
    _check_company_auto = True

    name = fields.Char(required=True, tracking=True)
    code = fields.Char(required=True, tracking=True,
                       help='Login code typed in the mobile app. Also used as invoice prefix.')
    active = fields.Boolean(default=True)
    phone = fields.Char()
    company_id = fields.Many2one('res.company', required=True, default=lambda self: self.env.company)
    currency_id = fields.Many2one(related='company_id.currency_id')
    partner_id = fields.Many2one(
        'res.partner', string='Rep as Customer', check_company=True, ondelete='restrict',
        help='Partner invoiced when goods are loaded into the van. Created automatically.')
    warehouse_id = fields.Many2one(
        'stock.warehouse', string='Main Warehouse', required=True, check_company=True,
        default=lambda self: self.env['stock.warehouse'].search(
            [('company_id', '=', self.env.company.id)], limit=1))
    van_location_id = fields.Many2one(
        'stock.location', string='Van Location', check_company=True, ondelete='restrict',
        domain="[('usage', '=', 'internal')]",
        help='Internal stock location representing the van. Created automatically.')
    load_pricelist_id = fields.Many2one(
        'product.pricelist', string='Loading Pricelist', check_company=True,
        help='Prices used when invoicing the rep for goods loaded into his van.')
    default_pricelist_id = fields.Many2one(
        'product.pricelist', string='Default Customer Pricelist', check_company=True,
        help='Selling prices for customers that have no pricelist of their own.')
    customer_ids = fields.One2many('res.partner', 'sales_rep_id', string='Customers')
    customer_count = fields.Integer(compute='_compute_counts')
    can_create_customers = fields.Boolean(default=True)

    pin_hash = fields.Char(groups='base.group_system', copy=False)
    pin = fields.Char(string='New PIN', compute='_compute_pin', inverse='_inverse_pin',
                      groups='sales_rep_mobile.group_rep_manager',
                      help='Type a new 4-8 digit PIN to change the rep login PIN.')
    token_hash = fields.Char(groups='base.group_system', copy=False, index=True)
    device_id = fields.Char(readonly=True, copy=False,
                            help='Device bound at last login. Reset to allow a new phone.')
    last_sync = fields.Datetime(readonly=True, copy=False)
    last_van_sale_seq = fields.Integer(
        readonly=True, copy=False,
        help='Highest van-sale number received from the device. Sent back at login so a '
             'reinstalled app continues numbering instead of reusing numbers.')

    load_ids = fields.One2many('sales.rep.load', 'rep_id')
    van_sale_ids = fields.One2many('sales.rep.van.sale', 'rep_id')
    visit_ids = fields.One2many('sales.rep.visit', 'rep_id')
    van_sale_count = fields.Integer(compute='_compute_counts')
    visit_count = fields.Integer(compute='_compute_counts')
    load_count = fields.Integer(compute='_compute_counts')
    balance = fields.Monetary(
        string='Rep Balance', compute='_compute_balance',
        help='What the rep owes the company (receivable on his partner).')

    _code_company_uniq = models.Constraint(
        'UNIQUE(code, company_id)', 'The rep code must be unique per company.')

    def _compute_counts(self):
        for rep in self:
            rep.customer_count = len(rep.customer_ids)
            rep.van_sale_count = len(rep.van_sale_ids)
            rep.visit_count = len(rep.visit_ids)
            rep.load_count = len(rep.load_ids)

    @api.depends('partner_id')
    def _compute_balance(self):
        for rep in self:
            rep.balance = rep.partner_id.sudo().credit if rep.partner_id else 0.0

    def _compute_pin(self):
        for rep in self:
            rep.pin = False

    def _inverse_pin(self):
        for rep in self:
            if rep.pin:
                rep._set_pin(rep.pin)

    def _set_pin(self, pin):
        pin = (pin or '').strip()
        if not pin.isdigit() or not 4 <= len(pin) <= 8:
            raise ValidationError(_('The PIN must be 4 to 8 digits.'))
        self.sudo().write({'pin_hash': _hash_pin(pin), 'token_hash': False})

    # ------------------------------------------------------------------
    # Creation: partner + van location
    # ------------------------------------------------------------------
    @api.model_create_multi
    def create(self, vals_list):
        reps = super().create(vals_list)
        for rep in reps:
            rep._ensure_partner_and_van()
        return reps

    def _ensure_partner_and_van(self):
        for rep in self:
            if not rep.partner_id:
                rep.partner_id = self.env['res.partner'].create({
                    'name': rep.name,
                    'ref': rep.code,
                    'phone': rep.phone,
                    'company_id': rep.company_id.id,
                    'is_sales_rep': True,
                })
            elif not rep.partner_id.is_sales_rep:
                rep.partner_id.is_sales_rep = True
            if not rep.van_location_id:
                rep.van_location_id = self.env['stock.location'].create({
                    'name': _('Van %s', rep.code),
                    'usage': 'internal',
                    'location_id': rep.warehouse_id.view_location_id.id,
                    'company_id': rep.company_id.id,
                })

    # ------------------------------------------------------------------
    # Authentication helpers (used by the API)
    # ------------------------------------------------------------------
    @api.model
    def _authenticate_pin(self, code, pin, device_id):
        rep = self.sudo().search([('code', '=', (code or '').strip()), ('active', '=', True)], limit=1)
        if not rep or not _check_pin(pin or '', rep.pin_hash):
            return False
        if rep.device_id and device_id and rep.device_id != device_id:
            raise UserError(_('This rep is bound to another phone. Ask the manager to reset the device.'))
        token = secrets.token_urlsafe(32)
        rep.write({'token_hash': hash_token(token), 'device_id': device_id or rep.device_id})
        return rep, token

    @api.model
    def _from_token(self, token):
        if not token:
            return self.browse()
        return self.sudo().search([('token_hash', '=', hash_token(token)), ('active', '=', True)], limit=1)

    def action_reset_device(self):
        self.sudo().write({'device_id': False, 'token_hash': False})

    # ------------------------------------------------------------------
    # Stock helpers
    # ------------------------------------------------------------------
    def _van_stock(self):
        """{product_id: qty} currently in the van."""
        self.ensure_one()
        return self._location_stock(self.van_location_id)

    def _main_stock(self):
        """{product_id: free qty} in the main warehouse (excluding vans)."""
        self.ensure_one()
        groups = self.env['stock.quant']._read_group(
            [('location_id', 'child_of', self.warehouse_id.lot_stock_id.id)],
            ['product_id'], ['quantity:sum', 'reserved_quantity:sum'])
        return {product.id: qty - reserved for product, qty, reserved in groups}

    def _location_stock(self, location):
        groups = self.env['stock.quant']._read_group(
            [('location_id', 'child_of', location.id)], ['product_id'], ['quantity:sum'])
        return {product.id: qty for product, qty in groups}

    def _customer_pricelist(self, partner):
        return partner.property_product_pricelist or self.default_pricelist_id

    # ------------------------------------------------------------------
    # Smart buttons
    # ------------------------------------------------------------------
    def _open(self, model, name):
        return {
            'type': 'ir.actions.act_window',
            'name': name,
            'res_model': model,
            'view_mode': 'list,form',
            'domain': [('rep_id', '=', self.id)],
            'context': {'default_rep_id': self.id},
        }

    def action_open_van_sales(self):
        return self._open('sales.rep.van.sale', _('Van Sales'))

    def action_open_visits(self):
        return self._open('sales.rep.visit', _('Visits'))

    def action_open_loads(self):
        return self._open('sales.rep.load', _('Van Loading'))

    def action_open_customers(self):
        return {
            'type': 'ir.actions.act_window',
            'name': _('Customers'),
            'res_model': 'res.partner',
            'view_mode': 'list,form',
            'domain': [('sales_rep_id', '=', self.id)],
            'context': {'default_sales_rep_id': self.id},
        }

    def action_open_van_stock(self):
        return {
            'type': 'ir.actions.act_window',
            'name': _('Van Stock'),
            'res_model': 'stock.quant',
            'view_mode': 'list',
            'domain': [('location_id', 'child_of', self.van_location_id.id)],
        }
