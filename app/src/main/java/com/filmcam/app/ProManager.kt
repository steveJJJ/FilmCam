package com.filmcam.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * 「工程模式」的一次性买断内购（Pro）。
 *
 * 设计要点，别随手改：
 * 1. **App 不需要 INTERNET 权限**。Play Billing 走的是与 Play 商店的 IPC（binder），
 *    真正的网络由 Play 商店自己完成。我们只声明 `com.android.vending.BILLING`，
 *    合并清单里由传递依赖注入的网络/位置权限在 AndroidManifest 里用 tools:node 剔掉。
 *    → 「数据物理上离不开这台手机」这句话依然成立，别为了省事把 INTERNET 加回来。
 * 2. **权益必须落盘**。购买成功后写 SharedPreferences，之后每次启动先读本地值再异步校验；
 *    断网、飞行模式、Play 服务临时不可用时都能正常进工程模式。
 * 3. **只在查询成功且确认没有该商品时才降级**，查询失败一律保持原状态，避免误伤已购用户。
 */
object ProManager {

    /** Play Console 里创建的一次性商品 ID，必须一字不差 */
    const val PRODUCT_ID = "pro_unlock"

    private const val TAG = "FILMCAM"
    private const val PREF = "filmcam"
    private const val KEY_PRO = "pro_unlocked"
    private const val KEY_PRICE = "pro_price"

    /** UI 刷新回调（价格/权益变化时触发） */
    var onUpdate: (() -> Unit)? = null

    /** 提示回调（Toast 文案） */
    var onMessage: ((String) -> Unit)? = null

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private var connecting = false

    fun isPro(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PRO, false)

    /** 商品价格，如 "¥18.00"；未查到返回上次缓存 */
    fun price(ctx: Context): String? =
        details?.oneTimePurchaseOfferDetails?.formattedPrice
            ?: prefs(ctx).getString(KEY_PRICE, null)

    /** 建立连接 → 查商品价格 → 恢复历史购买。可重复调用。 */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val c = client ?: BillingClient.newBuilder(app)
            .setListener { result, purchases -> handlePurchaseResult(app, result, purchases) }
            .enableAutoServiceReconnection()
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
            .also { client = it }

        if (c.isReady) {
            queryProducts(app)
            queryPurchases(app)
            return
        }
        if (connecting) return
        connecting = true
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts(app)
                    queryPurchases(app)
                } else {
                    Log.w(TAG, "Billing 连接失败 code=${result.responseCode} ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                Log.w(TAG, "Billing 服务断开，下次进入关于页会重连")
            }
        })
    }

    /** 发起购买。details 还没查到时先连一次，下次点击即可。 */
    fun buy(activity: Activity) {
        val d = details
        if (d == null) {
            onMessage?.invoke(activity.getString(R.string.pro_not_ready))
            start(activity)
            return
        }
        val offerToken = d.oneTimePurchaseOfferDetails?.offerToken
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(d)
            .apply { if (!offerToken.isNullOrEmpty()) setOfferToken(offerToken) }
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = client?.launchBillingFlow(activity, flowParams)
        if (result != null && result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "拉起购买失败 code=${result.responseCode} ${result.debugMessage}")
            onMessage?.invoke(activity.getString(R.string.pro_unavailable))
        }
    }

    private fun queryProducts(ctx: Context) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client?.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val d = queryResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                if (d != null) {
                    details = d
                    d.oneTimePurchaseOfferDetails?.formattedPrice?.let {
                        prefs(ctx).edit().putString(KEY_PRICE, it).apply()
                    }
                    onUpdate?.invoke()
                } else {
                    // 商品没在 Play Console 创建/未激活时会是空列表，这里不弹窗，静默记日志
                    Log.w(TAG, "未查到商品 $PRODUCT_ID（Play Console 里是否已创建并激活？）")
                }
            } else {
                Log.w(TAG, "查商品失败 code=${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private fun queryPurchases(ctx: Context) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client?.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // 查询失败（多半是没网/Play 服务异常）→ 保持本地权益，不降级
                Log.w(TAG, "恢复购买失败 code=${result.responseCode}，沿用本地权益")
                return@queryPurchasesAsync
            }
            val owned = purchases.any { p ->
                p.products.contains(PRODUCT_ID) &&
                    p.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            purchases.filter { it.products.contains(PRODUCT_ID) }.forEach { p ->
                if (!p.isAcknowledged) acknowledge(ctx, p)
            }
            setPro(ctx, owned)
        }
    }

    private fun handlePurchaseResult(
        ctx: Context,
        result: BillingResult,
        purchases: List<Purchase>?
    ) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { p ->
                    if (p.products.contains(PRODUCT_ID) &&
                        p.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        if (!p.isAcknowledged) acknowledge(ctx, p)
                        setPro(ctx, true)
                        onMessage?.invoke(ctx.getString(R.string.pro_unlock_ok))
                    }
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "用户取消购买")
            }

            else -> {
                Log.w(TAG, "购买失败 code=${result.responseCode} ${result.debugMessage}")
                onMessage?.invoke(ctx.getString(R.string.pro_unavailable))
            }
        }
    }

    /** 三天内不确认，Play 会自动退款。必须在购买回调里立刻 ack。 */
    private fun acknowledge(ctx: Context, purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client?.acknowledgePurchase(params) { r ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "确认购买失败 code=${r.responseCode}，下次启动会重试")
            }
        }
    }

    private fun setPro(ctx: Context, pro: Boolean) {
        val old = isPro(ctx)
        if (old == pro) {
            onUpdate?.invoke()
            return
        }
        prefs(ctx).edit().putBoolean(KEY_PRO, pro).apply()
        Log.i(TAG, "Pro 权益变更：$old -> $pro")
        onUpdate?.invoke()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
