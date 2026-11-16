from odoo import api, fields, models, _
from odoo.exceptions import UserError

from .stock_utils import create_done_picking


class SalesRepVanSale(models.Model):
    """A direct sale (or return) made by the rep from his van.

    Because the rep already *bought* the goods when loading the van, this is
    the rep's own sale document printed for the customer. It moves stock out
    of the van location but does not create a company invoice (that would
    count the revenue twice).
    """
    _name = 'sales.rep.van.sale'
    _description = 'Van Sale Invoice'
    _inherit = ['mail.thread']
    _order = 'date desc, id desc'
    _check_company_auto = True

    name = fields.Char(string='Number', required=True, readonly=True, copy=False,
                       help='Number generated on the phone, e.g. R05-000123.')
    mobile_uuid = fields.Char(required=True, copy=False, index=True, readonly=True)
    rep_id = fields.Many2one('sales.rep', required=True, check_company=True, readonly=True)
    company_id = fields.Many2one(related='rep_id.company_id', store=True)
    currency_id = fields.Many2one(related='company_id.currency_id')
    partner_id = fields.Many2one('res.partner', string='Customer', required=True, readonly=True)
    visit_id = fields.Many2one('sales.rep.visit', readonly=True)
    date = fields.Datetime(required=True, readonly=True)
    sale_type = fields.Selection([('sale', 'Sale'), ('return', 'Return')],
                                 required=True, default='sale', readonly=True)
    state = fields.Selection([('done', 'Done'), ('cancel', 'Cancelled')],
                             default='done', required=True, tracking=True)
    line_ids = fields.One2many('sales.rep.van.sale.line', 'sale_id', readonly=True)
    amount_untaxed = fields.Monetary(compute='_compute_amounts', store=True)
    amount_discount = fields.Monetary(string='Invoice Discount', readonly=True)
    amount_total = fields.Monetary(compute='_compute_amounts', store=True)
    amount_paid = fields.Monetary(readonly=True)
    amount_due = fields.Monetary(compute='_compute_amounts', store=True)
    signed_due = fields.Monetary(
        compute='_compute_amounts', store=True,
        help='Due amount, negative for returns. Used to compute the customer balance.')
    picking_id = fields.Many2one('stock.picking', readonly=True, copy=False)
    latitude = fields.Float(digits=(10, 7), readonly=True)
    longitude = fields.Float(digits=(10, 7), readonly=True)
    note = fields.Text()
    printed_count = fields.Integer(readonly=True)

    _mobile_uuid_uniq = models.Constraint('UNIQUE(mobile_uuid)', 'Duplicate van sale.')
    _name_rep_uniq = models.Constraint('UNIQUE(name, rep_id)', 'Van sale number already used.')

    @api.depends('line_ids.subtotal', 'amount_discount', 'amount_paid', 'sale_type', 'state')
    def _compute_amounts(self):
        for sale in self:
            untaxed = sum(sale.line_ids.mapped('subtotal'))
            sale.amount_untaxed = untaxed
            sale.amount_total = max(untaxed - sale.amount_discount, 0.0)
            sale.amount_due = sale.amount_total - sale.amount_paid
            sign = -1 if sale.sale_type == 'return' else 1
            sale.signed_due = sign * sale.amount_due if sale.state == 'done' else 0.0

    def _do_stock(self):
        for sale in self:
            rep = sale.rep_id
            van = rep.van_location_id
            customer_loc = (sale.partner_id.property_stock_customer
                            or self.env.ref('stock.stock_location_customers'))
            if sale.sale_type == 'sale':
                src, dest, ptype = van, customer_loc, rep.warehouse_id.out_type_id
            else:
                src, dest, ptype = customer_loc, van, rep.warehouse_id.in_type_id
            sale.picking_id = create_done_picking(
                self.env, ptype, src, dest,
                [(l.product_id, l.qty) for l in sale.line_ids],
                partner=sale.partner_id, origin=sale.name)

    def action_cancel(self):
        """Cancel and put the goods back (reverse stock move)."""
        for sale in self.filtered(lambda s: s.state == 'done'):
            if sale.picking_id:
                p = sale.picking_id
                create_done_picking(
                    self.env, p.picking_type_id.return_picking_type_id or p.picking_type_id,
                    p.location_dest_id, p.location_id,
                    [(m.product_id, m.quantity) for m in p.move_ids],
                    partner=sale.partner_id, origin=_('Cancel %s', sale.name))
            sale.state = 'cancel'

    @api.ondelete(at_uninstall=False)
    def _unlink_never(self):
        raise UserError(_('Van sales cannot be deleted. Cancel them instead.'))


class SalesRepVanSaleLine(models.Model):
    _name = 'sales.rep.van.sale.line'
    _description = 'Van Sale Line'

    sale_id = fields.Many2one('sales.rep.van.sale', required=True, ondelete='cascade', index=True)
    currency_id = fields.Many2one(related='sale_id.currency_id')
    product_id = fields.Many2one('product.product', required=True)
    uom_id = fields.Many2one(related='product_id.uom_id')
    qty = fields.Float(string='Quantity', digits='Product Unit')
    price_unit = fields.Float(digits='Product Price')
    discount = fields.Float(string='Discount (%)', digits='Discount')
    subtotal = fields.Monetary(compute='_compute_subtotal', store=True)

    @api.depends('qty', 'price_unit', 'discount')
    def _compute_subtotal(self):
        for line in self:
            line.subtotal = line.qty * line.price_unit * (1 - (line.discount or 0.0) / 100.0)
