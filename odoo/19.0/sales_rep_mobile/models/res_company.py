from odoo import fields, models


class ResCompany(models.Model):
    _inherit = 'res.company'

    rep_geofence_radius = fields.Integer(
        string='Visit Radius (m)', default=50,
        help='A visit opens automatically when the rep is within this distance '
             'of the customer, and closes when he moves away from it.')
    rep_geofence_exit_buffer = fields.Integer(
        string='Exit Buffer (m)', default=10,
        help='Extra distance beyond the radius before a visit closes. '
             'Prevents visits from flickering open/closed because of GPS noise.')
    rep_gps_max_accuracy = fields.Integer(
        string='Max GPS Accuracy (m)', default=40,
        help='GPS fixes less accurate than this are ignored for opening/closing visits.')
    rep_allow_manual_visit = fields.Boolean(
        string='Allow Manual Visits', default=True,
        help='Let reps open/close a visit by hand when GPS is unavailable. '
             'Manual visits are flagged for review.')
    rep_auto_confirm_orders = fields.Boolean(
        string='Auto-confirm Rep Orders', default=False,
        help='Confirm sales orders created from the app immediately. '
             'Otherwise they stay as quotations for the sales manager.')
    rep_load_journal_id = fields.Many2one(
        'account.journal', string='Rep Loading Journal',
        domain="[('type', '=', 'sale')]",
        help='Sales journal used for the invoices issued to reps when they load their van.')
    rep_api_user_id = fields.Many2one(
        'res.users', string='Integration User',
        help='Internal user that the mobile API acts as. Reps themselves are NOT '
             'Odoo users, so they do not consume user licences. '
             'Leave empty to use the superuser.')
