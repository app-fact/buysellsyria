"""Small helpers to create and validate pickings from code.

Kept defensive about field names so the module survives minor changes
between Odoo releases (e.g. stock.move ``name``).
"""
from odoo import Command


def move_vals(env, product, qty, src, dest, description=None):
    vals = {
        'product_id': product.id,
        'product_uom_qty': qty,
        'product_uom': product.uom_id.id,
        'location_id': src.id,
        'location_dest_id': dest.id,
    }
    if 'name' in env['stock.move']._fields:
        vals['name'] = description or product.display_name
    return vals


def create_done_picking(env, picking_type, src, dest, lines, partner=None, origin=None):
    """Create a picking moving ``lines`` [(product, qty)] from src to dest and validate it.

    Validation is forced: if the system stock is wrong (e.g. the rep sold
    offline more than Odoo thinks is in the van), the quant goes negative
    instead of blocking the sync. Managers fix it with an inventory count.
    """
    lines = [(p, q) for p, q in lines if q > 0]
    if not lines:
        return env['stock.picking']
    picking = env['stock.picking'].create({
        'picking_type_id': picking_type.id,
        'location_id': src.id,
        'location_dest_id': dest.id,
        'partner_id': partner.id if partner else False,
        'origin': origin,
        'move_ids': [Command.create(move_vals(env, p, q, src, dest)) for p, q in lines],
    })
    picking.action_confirm()
    picking.action_assign()
    for move in picking.move_ids:
        move.quantity = move.product_uom_qty
        move.picked = True
    picking.with_context(skip_backorder=True, cancel_backorder=True, skip_sms=True)._action_done()
    return picking
