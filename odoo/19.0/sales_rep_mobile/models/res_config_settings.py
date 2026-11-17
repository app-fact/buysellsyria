from odoo import fields, models


class ResConfigSettings(models.TransientModel):
    _inherit = 'res.config.settings'

    rep_geofence_radius = fields.Integer(related='company_id.rep_geofence_radius', readonly=False)
    rep_geofence_exit_buffer = fields.Integer(related='company_id.rep_geofence_exit_buffer', readonly=False)
    rep_gps_max_accuracy = fields.Integer(related='company_id.rep_gps_max_accuracy', readonly=False)
    rep_allow_manual_visit = fields.Boolean(related='company_id.rep_allow_manual_visit', readonly=False)
    rep_auto_confirm_orders = fields.Boolean(related='company_id.rep_auto_confirm_orders', readonly=False)
    rep_load_journal_id = fields.Many2one(related='company_id.rep_load_journal_id', readonly=False)
    rep_api_user_id = fields.Many2one(related='company_id.rep_api_user_id', readonly=False)
