from odoo import fields, models


class SaleOrder(models.Model):
    _inherit = 'sale.order'

    sales_rep_id = fields.Many2one('sales.rep', string='Van Sales Rep', index=True, copy=False,
                                   help='Rep who took this order from the mobile app.')
    rep_visit_id = fields.Many2one('sales.rep.visit', string='Rep Visit', copy=False)
    mobile_uuid = fields.Char(copy=False, index=True, readonly=True)

    _mobile_uuid_uniq = models.UniqueIndex('(mobile_uuid) WHERE mobile_uuid IS NOT NULL')
