from odoo import api, fields, models


class SalesRepVisit(models.Model):
    """A customer visit, opened/closed automatically by the phone's GPS
    (inside / outside the configured radius around the customer)."""
    _name = 'sales.rep.visit'
    _description = 'Rep Customer Visit'
    _order = 'check_in desc, id desc'
    _check_company_auto = True

    name = fields.Char(compute='_compute_name', store=True)
    mobile_uuid = fields.Char(required=True, copy=False, index=True, readonly=True)
    rep_id = fields.Many2one('sales.rep', required=True, check_company=True, readonly=True)
    company_id = fields.Many2one(related='rep_id.company_id', store=True)
    partner_id = fields.Many2one('res.partner', string='Customer', required=True, readonly=True)
    state = fields.Selection([('open', 'In Progress'), ('closed', 'Closed')],
                             default='open', required=True, readonly=True)
    check_in = fields.Datetime(required=True, readonly=True)
    check_out = fields.Datetime(readonly=True)
    duration = fields.Float(string='Duration (min)', compute='_compute_duration', store=True)
    in_latitude = fields.Float(digits=(10, 7), readonly=True)
    in_longitude = fields.Float(digits=(10, 7), readonly=True)
    in_distance = fields.Float(string='Distance at Check-in (m)', readonly=True)
    out_latitude = fields.Float(digits=(10, 7), readonly=True)
    out_longitude = fields.Float(digits=(10, 7), readonly=True)
    out_distance = fields.Float(string='Distance at Check-out (m)', readonly=True)
    open_mode = fields.Selection([('auto', 'GPS'), ('manual', 'Manual')], default='auto', readonly=True)
    close_mode = fields.Selection([('auto', 'GPS'), ('manual', 'Manual')], readonly=True)
    is_manual = fields.Boolean(compute='_compute_is_manual', store=True,
                               help='Opened or closed by hand: worth reviewing.')
    result = fields.Selection([
        ('sale', 'Van Sale'), ('order', 'Order'), ('collection', 'Collection'),
        ('no_sale', 'No Sale')], compute='_compute_result', store=True)
    no_sale_reason = fields.Char(readonly=True)
    note = fields.Text()
    van_sale_ids = fields.One2many('sales.rep.van.sale', 'visit_id')
    order_ids = fields.One2many('sale.order', 'rep_visit_id')
    collection_ids = fields.One2many('sales.rep.collection', 'visit_id')

    _mobile_uuid_uniq = models.Constraint('UNIQUE(mobile_uuid)', 'Duplicate visit.')

    @api.depends('partner_id', 'check_in')
    def _compute_name(self):
        for visit in self:
            visit.name = f'{visit.partner_id.name or ""} - {fields.Datetime.to_string(visit.check_in) or ""}'

    @api.depends('check_in', 'check_out')
    def _compute_duration(self):
        for visit in self:
            if visit.check_in and visit.check_out:
                visit.duration = (visit.check_out - visit.check_in).total_seconds() / 60.0
            else:
                visit.duration = 0.0

    @api.depends('open_mode', 'close_mode')
    def _compute_is_manual(self):
        for visit in self:
            visit.is_manual = 'manual' in (visit.open_mode, visit.close_mode)

    @api.depends('van_sale_ids', 'order_ids', 'collection_ids', 'state')
    def _compute_result(self):
        for visit in self:
            if visit.van_sale_ids:
                visit.result = 'sale'
            elif visit.order_ids:
                visit.result = 'order'
            elif visit.collection_ids:
                visit.result = 'collection'
            elif visit.state == 'closed':
                visit.result = 'no_sale'
            else:
                visit.result = False
