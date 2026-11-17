"""REST endpoints used by the Android app.

All endpoints take and return JSON. Authentication: ``Authorization: Bearer <token>``
obtained from /sales_rep/api/v1/login. Reps are not Odoo users; the token
identifies the ``sales.rep`` record.

The Odoo server must resolve the database without a login session
(single database, or ``dbfilter`` matching the domain).
"""
import logging

from odoo import http
from odoo.exceptions import AccessDenied, UserError, ValidationError
from odoo.http import request

_logger = logging.getLogger(__name__)

PREFIX = '/sales_rep/api/v1'


class SalesRepMobileController(http.Controller):

    # -- helpers --------------------------------------------------------
    def _json(self, data, status=200):
        return request.make_json_response(data, status=status)

    def _error(self, message, status=400, code='error'):
        return self._json({'error': {'code': code, 'message': message}}, status=status)

    def _body(self):
        try:
            return request.get_json_data() or {}
        except ValueError:
            return {}

    def _rep(self):
        header = request.httprequest.headers.get('Authorization', '')
        token = header[7:].strip() if header.lower().startswith('bearer ') else ''
        rep = request.env['sales.rep'].sudo()._from_token(token)
        if not rep:
            raise AccessDenied()
        return rep

    def _call(self, fn):
        try:
            return self._json(fn())
        except AccessDenied:
            return self._error('Invalid or expired session. Please log in again.', 401, 'unauthorized')
        except (UserError, ValidationError) as e:
            return self._error(e.args[0] if e.args else str(e), 400, 'business')
        except Exception as e:  # noqa: BLE001
            _logger.exception('Sales rep API error')
            return self._error(str(e), 500, 'server')

    # -- endpoints ------------------------------------------------------
    @http.route(f'{PREFIX}/ping', type='http', auth='public', methods=['GET'], csrf=False)
    def ping(self):
        return self._json({'ok': True, 'api_version': 1})

    @http.route(f'{PREFIX}/login', type='http', auth='public', methods=['POST'], csrf=False)
    def login(self):
        body = self._body()
        return self._call(lambda: request.env['sales.rep.mobile.api'].sudo().login(
            body.get('code'), body.get('pin'), body.get('device_id')))

    @http.route(f'{PREFIX}/pull', type='http', auth='public', methods=['POST'], csrf=False)
    def pull(self):
        body = self._body()
        return self._call(lambda: request.env['sales.rep.mobile.api'].sudo().pull(
            self._rep(), body.get('since')))

    @http.route(f'{PREFIX}/push', type='http', auth='public', methods=['POST'], csrf=False)
    def push(self):
        body = self._body()
        return self._call(lambda: request.env['sales.rep.mobile.api'].sudo().push(
            self._rep(), body.get('ops') or []))
