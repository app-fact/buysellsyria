from odoo.exceptions import UserError
from odoo.tests import TransactionCase, tagged


@tagged('post_install', '-at_install')
class TestMobileApi(TransactionCase):

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.api = cls.env['sales.rep.mobile.api']
        cls.warehouse = cls.env['stock.warehouse'].search([('company_id', '=', cls.env.company.id)], limit=1)
        cls.product = cls.env['product.product'].create({
            'name': 'Rice 1kg', 'type': 'consu', 'is_storable': True,
            'list_price': 10.0, 'sale_ok': True,
        })
        cls.env['stock.quant']._update_available_quantity(
            cls.product, cls.warehouse.lot_stock_id, 100.0)
        cls.rep = cls.env['sales.rep'].create({
            'name': 'Ahmad', 'code': 'R01', 'warehouse_id': cls.warehouse.id,
        })
        cls.rep._set_pin('1234')
        cls.customer = cls.env['res.partner'].create({
            'name': 'Shop 1', 'sales_rep_id': cls.rep.id,
            'partner_latitude': 33.5138, 'partner_longitude': 36.2765,
        })

    def _push(self, *ops):
        return self.api.push(self.rep, list(ops))['results']

    def test_rep_creation(self):
        self.assertTrue(self.rep.partner_id.is_sales_rep)
        self.assertEqual(self.rep.van_location_id.usage, 'internal')

    def test_login(self):
        res = self.api.login('R01', '1234', 'phone-1')
        self.assertTrue(res['token'])
        self.assertEqual(self.env['sales.rep']._from_token(res['token']), self.rep)
        with self.assertRaises(UserError):
            self.api.login('R01', '9999', 'phone-1')
        with self.assertRaises(UserError):  # another phone
            self.api.login('R01', '1234', 'phone-2')

    def _load(self, qty=20):
        load = self.env['sales.rep.load'].create({
            'rep_id': self.rep.id,
            'line_ids': [(0, 0, {'product_id': self.product.id, 'qty': qty})],
        })
        load.action_done()
        return load

    def test_load_invoices_rep_and_moves_stock(self):
        load = self._load(20)
        self.assertEqual(load.state, 'done')
        self.assertEqual(self.rep._van_stock().get(self.product.id), 20)
        self.assertEqual(load.invoice_id.partner_id, self.rep.partner_id)
        self.assertEqual(load.invoice_id.state, 'posted')
        self.assertAlmostEqual(load.invoice_id.amount_untaxed, 200.0)

    def test_van_sale_and_idempotency(self):
        self._load(20)
        op = {'uuid': 'op-sale-1', 'type': 'van_sale', 'payload': {
            'number': 'R01-000001', 'seq': 1, 'customer_id': self.customer.id,
            'date': '2026-11-16T09:30:00Z', 'paid': 20,
            'lines': [{'product_id': self.product.id, 'qty': 5, 'price_unit': 10}],
        }}
        res = self._push(op)
        self.assertEqual(res[0]['status'], 'ok', res)
        sale = self.env['sales.rep.van.sale'].browse(res[0]['res_id'])
        self.assertEqual(sale.amount_total, 50)
        self.assertEqual(sale.amount_due, 30)
        self.assertEqual(self.rep._van_stock().get(self.product.id), 15)
        self.assertEqual(self.rep.last_van_sale_seq, 1)
        self.customer.invalidate_recordset()
        self.assertEqual(self.customer.rep_customer_due, 30)
        # resend: no duplicate
        res2 = self._push(op)
        self.assertTrue(res2[0].get('duplicate'))
        self.assertEqual(self.env['sales.rep.van.sale'].search_count([('rep_id', '=', self.rep.id)]), 1)

    def test_offline_customer_then_visit_then_order(self):
        res = self._push(
            {'uuid': 'c-1', 'type': 'customer_upsert', 'payload': {
                'customer_uuid': 'cust-uuid-1', 'name': 'New Shop', 'phone': '0999',
                'latitude': 33.5, 'longitude': 36.3}},
            {'uuid': 'v-1', 'type': 'visit_open', 'payload': {
                'visit_uuid': 'visit-1', 'customer_uuid': 'cust-uuid-1',
                'check_in': '2026-11-16T10:00:00Z', 'distance': 12}},
            {'uuid': 'o-1', 'type': 'sale_order', 'payload': {
                'customer_uuid': 'cust-uuid-1', 'visit_uuid': 'visit-1',
                'lines': [{'product_id': self.product.id, 'qty': 30}]}},
            {'uuid': 'v-2', 'type': 'visit_close', 'payload': {
                'visit_uuid': 'visit-1', 'check_out': '2026-11-16T10:20:00Z', 'distance': 70}},
        )
        self.assertEqual([r['status'] for r in res], ['ok'] * 4, res)
        visit = self.env['sales.rep.visit'].search([('mobile_uuid', '=', 'visit-1')])
        self.assertEqual(visit.state, 'closed')
        self.assertAlmostEqual(visit.duration, 20.0)
        self.assertEqual(visit.result, 'order')
        order = self.env['sale.order'].browse(res[2]['res_id'])
        self.assertEqual(order.sales_rep_id, self.rep)
        self.assertEqual(order.partner_id.sales_rep_id, self.rep)

    def test_errors_do_not_block_other_ops(self):
        other = self.env['res.partner'].create({'name': 'Not mine'})
        res = self._push(
            {'uuid': 'bad-1', 'type': 'collection', 'payload': {'customer_id': other.id, 'amount': 5}},
            {'uuid': 'good-1', 'type': 'collection', 'payload': {
                'customer_id': self.customer.id, 'amount': 5, 'number': 'R01-C-1'}},
            {'uuid': 'bad-2', 'type': 'nonsense', 'payload': {}},
        )
        self.assertEqual([r['status'] for r in res], ['error', 'ok', 'error'])

    def test_load_request_and_pull(self):
        res = self._push({'uuid': 'l-1', 'type': 'load_request', 'payload': {
            'lines': [{'product_id': self.product.id, 'qty': 7}]}})
        load = self.env['sales.rep.load'].browse(res[0]['res_id'])
        self.assertEqual(load.state, 'requested')
        load.action_done()
        data = self.api.pull(self.rep)
        self.assertTrue(data['full'])
        self.assertIn(self.product.id, data['product_ids'])
        self.assertEqual(data['van_stock'][str(self.product.id)], 7)
        self.assertEqual(data['main_stock'][str(self.product.id)], 93)
        self.assertIn(self.customer.id, data['customer_ids'])
        self.assertEqual(data['config']['geofence_radius'], 50)
        delta = self.api.pull(self.rep, data['server_time'])
        self.assertFalse(delta['full'])
