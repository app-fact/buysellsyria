from odoo import fields, models


class SalesRepCollection(models.Model):
    """Money collected by the rep from a customer for earlier van sales on credit."""
    _name = 'sales.rep.collection'
    _description = 'Rep Collection from Customer'
    _order = 'date desc, id desc'
    _check_company_auto = True

    name = fields.Char(required=True, readonly=True)
    mobile_uuid = fields.Char(required=True, copy=False, index=True, readonly=True)
    rep_id = fields.Many2one('sales.rep', required=True, check_company=True, readonly=True)
    company_id = fields.Many2one(related='rep_id.company_id', store=True)
    currency_id = fields.Many2one(related='company_id.currency_id')
    partner_id = fields.Many2one('res.partner', string='Customer', required=True, readonly=True)
    visit_id = fields.Many2one('sales.rep.visit', readonly=True)
    date = fields.Datetime(required=True, readonly=True)
    amount = fields.Monetary(required=True, readonly=True)
    state = fields.Selection([('done', 'Done'), ('cancel', 'Cancelled')], default='done', required=True)
    note = fields.Text()

    _mobile_uuid_uniq = models.Constraint('UNIQUE(mobile_uuid)', 'Duplicate collection.')
    _amount_positive = models.Constraint('CHECK(amount > 0)', 'The amount must be positive.')

    def action_cancel(self):
        self.state = 'cancel'
