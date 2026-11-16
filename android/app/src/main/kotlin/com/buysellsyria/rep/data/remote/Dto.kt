package com.buysellsyria.rep.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RepDto(
    val id: Long,
    val name: String,
    val code: String,
    val phone: String = "",
    val invoice_prefix: String,
    val last_van_sale_seq: Int = 0,
    val can_create_customers: Boolean = true,
)

@Serializable
data class CompanyDto(val name: String, val phone: String = "", val address: String = "", val vat: String = "")

@Serializable
data class CurrencyDto(val name: String, val symbol: String, val decimals: Int = 0)

@Serializable
data class ConfigDto(
    val geofence_radius: Int = 50,
    val geofence_exit_buffer: Int = 10,
    val gps_max_accuracy: Int = 40,
    val allow_manual_visit: Boolean = true,
)

@Serializable
data class LoginResponse(
    val token: String,
    val rep: RepDto,
    val company: CompanyDto,
    val currency: CurrencyDto,
    val config: ConfigDto,
)

@Serializable
data class ProductDto(
    val id: Long,
    val name: String,
    val default_code: String = "",
    val barcode: String = "",
    val uom: String = "",
    val category: String = "",
    val list_price: Double = 0.0,
    val load_price: Double = 0.0,
)

@Serializable
data class CustomerDto(
    val id: Long,
    val uuid: String? = null,
    val name: String,
    val phone: String = "",
    val street: String = "",
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val pricelist_id: Long? = null,
    val due: Double = 0.0,
)

@Serializable
data class LoadLineDto(val product_id: Long, val product: String, val qty: Double, val price_unit: Double = 0.0)

@Serializable
data class LoadDto(
    val id: Long,
    val uuid: String? = null,
    val name: String,
    val type: String,
    val state: String,
    val date: String? = null,
    val amount_total: Double = 0.0,
    val invoice: String? = null,
    val lines: List<LoadLineDto> = emptyList(),
)

@Serializable
data class PullResponse(
    val server_time: String,
    val full: Boolean,
    val rep: RepDto,
    val company: CompanyDto,
    val currency: CurrencyDto,
    val config: ConfigDto,
    val rep_balance: Double = 0.0,
    val default_pricelist_id: Long? = null,
    val products: List<ProductDto> = emptyList(),
    val product_ids: List<Long> = emptyList(),
    val customers: List<CustomerDto> = emptyList(),
    val customer_ids: List<Long> = emptyList(),
    val customer_dues: Map<String, Double> = emptyMap(),
    val prices: Map<String, Map<String, Double>> = emptyMap(),
    val van_stock: Map<String, Double> = emptyMap(),
    val main_stock: Map<String, Double> = emptyMap(),
    val loads: List<LoadDto> = emptyList(),
)

@Serializable
data class OpDto(val uuid: String, val type: String, val payload: JsonObject, val created_at: String)

@Serializable
data class PushRequest(val ops: List<OpDto>)

@Serializable
data class OpResult(
    val uuid: String? = null,
    val status: String,
    val message: String? = null,
    val res_model: String? = null,
    val res_id: Long? = null,
    val name: String? = null,
    val duplicate: Boolean = false,
)

@Serializable
data class PushResponse(val results: List<OpResult>)
