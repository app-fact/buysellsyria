from odoo import Command, api, fields, models, _
from odoo.exceptions import UserError

from .stock_utils import create_done_picking


class SalesRepLoad(models.Model):
    """Loading goods into a van (or unloading them back).

    Loading = internal transfer Main Warehouse -> Van + a customer invoice
    to the rep's partner, exactly as if the rep bought the goods.
    Unloading = transfer Van -> Main Warehouse + a credit note to the rep.
    """
    _name = 'sales.rep.load'
    _description = 'Van Loading / Unloading'
    _inherit = ['mail.thread']
    _order = 'date desc, id desc'
    _check_company_auto = True

    name = fields.Char(default='/', readonly=True, copy=False)
    rep_id = fields.Many2one('sales.rep', required=True, check_company=True, tracking=True)
    company_id = fields.Many2one(related='rep_id.company_id', store=True)
    currency_id = fields.Many2one(related='company_id.currency_id')
    load_type = fields.Selection(
        [('load', 'Load (Warehouse → Van)'), ('unload', 'Unload (Van → Warehouse)')],
        required=True, default='load', tracking=True)
    state = fields.Selection(
        [('draft', 'Draft'), ('requested', 'Requested by Rep'), ('done', 'Done'), ('cancel', 'Cancelled')],
        default='draft', required=True, tracking=True, copy=False)
    date = fields.Datetime(default=fields.Datetime.now, required=True)
    line_ids = fields.One2many('sales.rep.load.line', 'load_id', copy=True)
    picking_id = fields.Many2one('stock.picking', readonly=True, copy=False)
    invoice_id = fields.Many2one('account.move', readonly=True, copy=False)
    amount_total = fields.Monetary(compute='_compute_amount', store=True)
    note = fields.Text()
    mobile_uuid = fields.Char(copy=False, index=True, readonly=True)

    _mobile_uuid_uniq = models.UniqueIndex('(mobile_uuid) WHERE mobile_uuid IS NOT NULL')

    @api.depends('line_ids.subtotal')
    def _compute_amount(self):
        for load in self:
            load.amount_total = sum(load.line_ids.mapped('subtotal'))

    @api.model_create_multi
    def create(self, vals_list):
        for vals in vals_list:
            if vals.get('name', '/') == '/':
                vals['name'] = self.env['ir.sequence'].next_by_code('sales.rep.load') or '/'
        return super().create(vals_list)

    def action_request(self):
        self.filtered(lambda l: l.state == 'draft').state = 'requested'

    def action_cancel(self):
        if self.filtered(lambda l: l.state == 'done'):
            raise UserError(_('A validated loading cannot be cancelled. Create an unloading instead.'))
        self.state = 'cancel'

    def action_reset_draft(self):
        self.filtered(lambda l: l.state == 'cancel').state = 'draft'

    def action_fill_prices(self):
        for line in self.line_ids:
            line.price_unit = line._default_price()

    def action_done(self):
        for load in self:
            if load.state not in ('draft', 'requested'):
                continue
            if not load.line_ids:
                raise UserError(_('Add at least one product.'))
            for line in load.line_ids.filtered(lambda l: not l.price_unit):
                line.price_unit = line._default_price()
            load._do_transfer()
            load._do_invoice()
            load.state = 'done'
        return True

    def _do_transfer(self):
        self.ensure_one()
        rep = self.rep_id
        main = rep.warehouse_id.lot_stock_id
        van = rep.van_location_id
        src, dest = (main, van) if self.load_type == 'load' else (van, main)
        self.picking_id = create_done_picking(
            self.env, rep.warehouse_id.int_type_id, src, dest,
            [(l.product_id, l.qty) for l in self.line_ids],
            partner=rep.partner_id, origin=self.name)

    def _do_invoice(self):
        self.ensure_one()
        rep = self.rep_id
        vals = {
            'move_type': 'out_invoice' if self.load_type == 'load' else 'out_refund',
            'partner_id': rep.partner_id.id,
            'invoice_origin': self.name,
            'company_id': rep.company_id.id,
            'invoice_line_ids': [Command.create({
                'product_id': l.product_id.id,
                'quantity': l.qty,
                'price_unit': l.price_unit,
            }) for l in self.line_ids if l.qty > 0],
        }
        journal = rep.company_id.rep_load_journal_id
        if journal:
            vals['journal_id'] = journal.id
        invoice = self.env['account.move'].create(vals)
        invoice.action_post()
        self.invoice_id = invoice

    def action_view_invoice(self):
        self.ensure_one()
        return {
            'type': 'ir.actions.act_window',
            'res_model': 'account.move',
            'res_id': self.invoice_id.id,
            'view_mode': 'form',
        }

    def action_view_picking(self):
        self.ensure_one()
        return {
            'type': 'ir.actions.act_window',
            'res_model': 'stock.picking',
            'res_id': self.picking_id.id,
            'view_mode': 'form',
        }


class SalesRepLoadLine(models.Model):
    _name = 'sales.rep.load.line'
    _description = 'Van Loading Line'

    load_id = fields.Many2one('sales.rep.load', required=True, ondelete='cascade', index=True)
    currency_id = fields.Many2one(related='load_id.currency_id')
    product_id = fields.Many2one('product.product', required=True,
                                 domain="[('is_storable', '=', True)]")
    uom_id = fields.Many2one(related='product_id.uom_id')
    qty = fields.Float(string='Quantity', digits='Product Unit', default=1.0)
    price_unit = fields.Float(digits='Product Price')
    subtotal = fields.Monetary(compute='_compute_subtotal', store=True)

    @api.depends('qty', 'price_unit')
    def _compute_subtotal(self):
        for line in self:
            line.subtotal = line.qty * line.price_unit

    @api.onchange('product_id')
    def _onchange_product_id(self):
        if self.product_id:
            self.price_unit = self._default_price()

    def _default_price(self):
        rep = self.load_id.rep_id
        if rep.load_pricelist_id:
            return rep.load_pricelist_id._get_product_price(
                self.product_id, self.qty or 1.0, partner=rep.partner_id)
        return self.product_id.lst_price
