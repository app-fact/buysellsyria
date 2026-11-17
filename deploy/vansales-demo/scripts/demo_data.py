# Van-sales demo data for vansales.prolabtech.net (Odoo 20 + sales_rep_mobile).
#
# Run by install.sh through `odoo shell` (stdin). Environment:
#   ADMIN_LOGIN, ADMIN_PASSWORD, MANAGER_LOGIN, MANAGER_PASSWORD, R01_PIN, R02_PIN, DRY_RUN (1 = rollback)
# Lines starting with "CRED " are credentials: install.sh moves them into CREDENTIALS.txt (mode 600)
# and never prints them to the terminal.
import os

DRY_RUN = os.environ.get('DRY_RUN') == '1'
E = os.environ

if env['sales.rep'].search_count([('code', '=', 'R01')]):
    print('Demo data already present (rep R01 exists) - nothing to do.')
    raise SystemExit(0)

env = env(context=dict(env.context, tracking_disable=True, mail_create_nolog=True, mail_notrack=True,
                       lang='ar_SY'))
company = env.company
syria = env.ref('base.sy')
syp = env.ref('base.SYP')

# --- company -------------------------------------------------------------
company.write({
    'name': 'شركة الشام للمواد الغذائية (ديمو)',
    'country_id': syria.id,
    'city': 'دمشق',
    'street': 'المنطقة الصناعية - القدم',
    'phone': '+963 11 000 0000',
    'rep_geofence_radius': 50,
    'rep_geofence_exit_buffer': 10,
    'rep_gps_max_accuracy': 40,
    'rep_allow_manual_visit': True,
})
if company.currency_id != syp and not env['account.move.line'].search_count([('company_id', '=', company.id)]):
    syp.active = True
    company.currency_id = syp
print('Company:', company.name, company.currency_id.name)

# --- users ----------------------------------------------------------------
admin = env.ref('base.user_admin')
admin.write({'login': E['ADMIN_LOGIN'], 'password': E['ADMIN_PASSWORD'], 'name': 'مدير النظام',
             'lang': 'ar_SY', 'tz': 'Asia/Damascus'})
print(f"CRED admin login={E['ADMIN_LOGIN']} password={E['ADMIN_PASSWORD']}")

manager_groups = [env.ref(x).id for x in (
    'sales_rep_mobile.group_rep_manager', 'sales_team.group_sale_manager',
    'stock.group_stock_manager', 'account.group_account_invoice')]
manager = env['res.users'].create({
    'name': 'مدير المبيعات',
    'login': E['MANAGER_LOGIN'],
    'password': E['MANAGER_PASSWORD'],
    'lang': 'ar_SY',
    'tz': 'Asia/Damascus',
    'group_ids': [(4, g) for g in manager_groups],
})
print(f"CRED manager login={E['MANAGER_LOGIN']} password={E['MANAGER_PASSWORD']}")
# the mobile API acts as the manager user (no licence needed for the reps themselves)
company.rep_api_user_id = manager

# --- products ---------------------------------------------------------------
Categ = env['product.category']
food = Categ.create({'name': 'مواد غذائية'})
cats = {n: Categ.create({'name': n, 'parent_id': food.id}) for n in ('حبوب', 'زيوت', 'مشروبات', 'معلبات', 'منظفات')}
items = [
    # name, category, list price (SYP), load price, barcode, qty in main warehouse
    ('رز مصري 1 كغ', 'حبوب', 18000, 16000, '6210000000011', 500),
    ('سكر 1 كغ', 'حبوب', 12000, 10500, '6210000000028', 800),
    ('برغل خشن 1 كغ', 'حبوب', 9000, 7800, '6210000000035', 300),
    ('معكرونة 500 غ', 'حبوب', 7000, 6000, '6210000000042', 600),
    ('زيت دوار الشمس 1.8 ل', 'زيوت', 45000, 41000, '6210000000059', 250),
    ('سمنة نباتية 1 كغ', 'زيوت', 38000, 34500, '6210000000066', 150),
    ('شاي أسود 500 غ', 'مشروبات', 32000, 29000, '6210000000073', 200),
    ('متة 500 غ', 'مشروبات', 25000, 22500, '6210000000080', 400),
    ('معجون بندورة 800 غ', 'معلبات', 15000, 13200, '6210000000097', 350),
    ('حمص حب معلب 400 غ', 'معلبات', 8000, 7000, '6210000000103', 300),
    ('مسحوق غسيل 2.5 كغ', 'منظفات', 55000, 50000, '6210000000110', 120),
]
warehouse = env['stock.warehouse'].search([('company_id', '=', company.id)], limit=1)
products = env['product.product']
for name, cat, price, _load, barcode, _qty in items:
    products |= env['product.product'].create({
        'name': name, 'categ_id': cats[cat].id, 'type': 'consu', 'is_storable': True,
        'list_price': price, 'standard_price': round(_load * 0.85), 'barcode': barcode, 'sale_ok': True,
    })
for product, (*_, qty) in zip(products, items):
    env['stock.quant'].with_context(inventory_mode=True).create({
        'product_id': product.id, 'location_id': warehouse.lot_stock_id.id, 'inventory_quantity': qty,
    }).action_apply_inventory()
print('Products:', len(products))

# --- pricelists ---------------------------------------------------------------
Pricelist = env['product.pricelist']
load_pl = Pricelist.create({
    'name': 'أسعار التحميل للمندوبين', 'currency_id': company.currency_id.id,
    'item_ids': [(0, 0, {'applied_on': '0_product_variant', 'product_id': p.id,
                         'compute_price': 'fixed', 'fixed_price': it[3]})
                 for p, it in zip(products, items)],
})
retail_pl = Pricelist.create({'name': 'سعر المفرق', 'currency_id': company.currency_id.id})

# --- reps ---------------------------------------------------------------
Rep = env['sales.rep']
reps = {}
for code, name, phone in (('R01', 'أحمد الحلبي', '0944000001'), ('R02', 'خالد الشامي', '0944000002')):
    rep = Rep.create({'name': name, 'code': code, 'phone': phone, 'warehouse_id': warehouse.id,
                      'load_pricelist_id': load_pl.id, 'default_pricelist_id': retail_pl.id})
    rep._set_pin(E[f'{code}_PIN'])
    reps[code] = rep
    print(f"CRED rep code={code} pin={E[f'{code}_PIN']}")

# --- customers (Damascus shops, real-looking coordinates) ---------------------------
shops = [
    ('R01', 'بقالية أبو سمير', 'المزة - الشيخ سعد', 33.50267, 36.25389),
    ('R01', 'سوبرماركت الفردوس', 'المزة - فيلات غربية', 33.50050, 36.24585),
    ('R01', 'محل الأمانة للمواد الغذائية', 'كفرسوسة', 33.49710, 36.26960),
    ('R01', 'ميني ماركت الريان', 'المالكي', 33.52350, 36.27910),
    ('R02', 'بقالية الخير', 'باب توما', 33.51330, 36.31540),
    ('R02', 'سوبرماركت النور', 'القصاع', 33.51960, 36.31010),
    ('R02', 'محلات الهدى', 'ركن الدين', 33.53690, 36.30110),
    ('R02', 'بقالية الصباح', 'جرمانا', 33.48630, 36.34560),
]
for code, name, street, lat, lng in shops:
    env['res.partner'].create({
        'name': name, 'street': street, 'city': 'دمشق', 'country_id': syria.id,
        'partner_latitude': lat, 'partner_longitude': lng, 'customer_rank': 1,
        'sales_rep_id': reps[code].id, 'property_product_pricelist': retail_pl.id,
        'phone': '0933' + str(abs(hash(name)) % 1000000).zfill(6),
    })
print('Customers:', len(shops))

# --- first van load for R01 (stock moved + invoice to the rep) -----------------------
load = env['sales.rep.load'].create({
    'rep_id': reps['R01'].id,
    'line_ids': [(0, 0, {'product_id': p.id, 'qty': q})
                 for p, q in zip(products, (40, 60, 20, 50, 20, 10, 15, 30, 25, 20, 8))],
    'note': 'تحميل أول يوم (ديمو)',
})
load.action_done()
print('Load', load.name, 'invoice', load.invoice_id.name, 'amount', load.amount_total)
# a pending request for R02 so the warehouse validation flow can be shown live
env['sales.rep.load'].create({
    'rep_id': reps['R02'].id, 'state': 'requested',
    'line_ids': [(0, 0, {'product_id': p.id, 'qty': 20}) for p in products[:6]],
    'note': 'طلب تحميل من المندوب (ديمو)',
}).action_fill_prices()

if DRY_RUN:
    env.cr.rollback()
    print('DRY RUN - rolled back')
else:
    env.cr.commit()
    print('Demo data committed.')
