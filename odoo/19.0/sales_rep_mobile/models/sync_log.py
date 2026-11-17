from odoo import fields, models


class SalesRepSyncLog(models.Model):
    """One row per operation pushed by a phone. Makes the push idempotent:
    if the phone resends an operation (lost response, retry), the stored
    result is returned instead of creating a duplicate."""
    _name = 'sales.rep.sync.log'
    _description = 'Mobile Sync Operation'
    _order = 'id desc'

    rep_id = fields.Many2one('sales.rep', required=True, index=True, ondelete='cascade')
    uuid = fields.Char(required=True, index=True)
    op_type = fields.Char(required=True)
    state = fields.Selection([('ok', 'OK'), ('error', 'Error')], required=True)
    res_model = fields.Char()
    res_id = fields.Integer()
    message = fields.Text()
    payload = fields.Text()
    client_date = fields.Char()

    _uuid_uniq = models.UniqueIndex('(uuid) WHERE state = \'ok\'')
