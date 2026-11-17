package com.anrays.yplus

/**
 * 框架能力 API 协议（消费端 ↔ 宿主）。
 *
 * 传输：消费端 `startService` 到 [FrameUpdateHostService]，`cmd="api"`，
 *       extras：`id`(UUID) / `api`(字符串) / `params`(JSON 字符串)。
 * 响应：宿主经 [FrameBusinessLink.pushApiResult] 推
 *       `{action:"api_result", id, ok, data|error, message}`。
 * 事件：长任务经 [FrameBusinessLink.pushApiEvent] 推
 *       `{action:"api_event", id, event, data}`（如 app.install 的 progress/done/failed）。
 *
 * 版本：
 *   · **v=1**：`apis` 是扁平字符串数组（只有名字，只能判"有没有"）。
 *   · **v=2**（2026-10-29）：`apis` 改为 `[{name, since}]` —— 消费端能判断"这条 api 从哪一版开始有"，
 *     也能为将来"同名 api 加参数"做兼容。**只增不减**：老 api 名字全部保留，老消费端按名字匹配仍可用
 *     （它读到的会是对象数组，所以 v=1 的消费端必须按 v 决定解析方式；业务应用已同步支持两种）。
 *   · **v=3**（2026-10-30）：加 [API_FRAME_KV]，`app.install` 加**不透明 `extra`**（原样回显进安装回执）。
 *     这两个是**通用底座**，目的是让"业务侧自己就能解决的事"不再回头要求宿主加 api：
 *     · `extra` → 任何"这次调用和后来的结果怎么对上号"的需求（安装回执认领、任务关联…）都走它；
 *     · `frame.kv` → 任何"要一份不受业务沙箱数据生命周期影响的小状态"（安装记账、迁移标记…）都走它。
 *
 * 铁律：**新增能力一律加新 api，不改老 api 的语义/参数**（与 JS 桥同纪律）。
 *       宿主不认识的新 api 返回 [ERR_UNSUPPORTED]，消费端据此降级 —— 宿主/业务版本可以各走各的。
 */
object FrameApi {

    const val VERSION = 3

    /** 统一入口命令（消费端 startService 时放在 "cmd" extra） */
    const val CMD_API = "api"

    const val EXTRA_ID = "id"
    const val EXTRA_API = "api"
    const val EXTRA_PARAMS = "params"

    /** 一条 api 的登记项：名字 + 起始协议版本（消费端据此判能否用某个参数） */
    data class ApiEntry(val name: String, val since: Int)

    // ---------------- api 名 ----------------

    // 通用
    const val API_CAPABILITIES = "frame.capabilities"
    const val API_DEVICE_INFO = "device.info"

    /** 轻量探活：只回 `{ok, v}`，用于"宿主在不在"而不必拉全量状态 */
    const val API_FRAME_PING = "frame.ping"

    /** 写一条日志到**宿主** logcat（`FrameApiHost` tag）：跨进程排查专用，业务自己的 Log 不在宿主进程里 */
    const val API_FRAME_LOG = "frame.log"

    // 沙箱应用
    /**
     * 下载并安装 APK：`{source:"url"|"path", url|path, clear?, extra?}`
     *
     * `extra`（v3 起，可选）：**任意 JSON**，宿主不解释、原样回显到安装回执的 `data.extra`。
     * 业务拿它给这次安装打标记（如 `{seed, url}`），装完进程被杀、重启后靠 `app.installStatus`
     * 读回来就能精确认定"这条回执就是那次点击装的"，不需要宿主懂任何业务语义。
     */
    const val API_APP_INSTALL = "app.install"
    const val API_APP_INSTALL_STATUS = "app.installStatus"
    const val API_APP_LIST = "app.list"
    const val API_APP_LAUNCH = "app.launch"
    const val API_APP_UNINSTALL = "app.uninstall"

    /** 单个包的详情：`{packageName}` → `{packageName,label,versionCode,versionName,installed}` */
    const val API_APP_INFO = "app.info"

    /**
     * 只清数据、不装包：`{packageName}` → `{ok}`。
     * （宿主 `BoxRepository.clearData` 早就有了，只是以前没暴露；清单 `mode=clear` 的"清数据安装"
     *  仍然走 `app.install` 的 `clear` 参数，不要拆成两步 —— 中间失败会留下"数据没了、包还是旧的"。）
     */
    const val API_APP_CLEAR_DATA = "app.clearData"

    // 权限
    const val API_PERMS_GET = "perms.get"
    const val API_PERMS_OPEN_SETTINGS = "perms.openSettings"

    /**
     * 宿主代申请**运行时权限**：`{perms:["android.permission.RECORD_AUDIO", …]}`。
     * 🔴 沙箱内业务的 `requestPermissions` 是被 BlackBox 伪造的；录音/定位/READ_LOGS 实际全按**宿主 uid** 校验，
     *    只有宿主真正持有才算数（见宿主 `MainActivity.requestMissingPermissions`）。
     * 返回：`{granted:{perm:bool}, missing:[…], asked:bool, opened:bool}`
     *      · `asked=true`：已拉起系统授权框（结果在 `granted` 里，可能还是 false —— 用户没点）
     *      · `opened=true`：宿主界面不在前台，改为打开"应用详情"页，需要用户手动授予
     */
    const val API_PERMS_REQUEST = "perms.request"

    // 通知
    const val API_NOTIFY_STATE = "notify.state"
    const val API_NOTIFY_TOAST = "notify.toast"
    const val API_NOTIFY_POST = "notify.post"

    /** 撤销 `notify.post` 发的通知：`{id}` */
    const val API_NOTIFY_CANCEL = "notify.cancel"

    // 无障碍（宿主代执行）
    const val API_ACCESS_STATE = "access.state"
    const val API_ACCESS_PERFORM = "access.perform"

    /**
     * 注入**坐标手势**：`{op:"tap"|"swipe", x1,y1,x2,y2, duration}`（duration 毫秒，默认 tap=50 / swipe=300）。
     * 盒子里业务自带的无障碍服务绑不上系统（只有真实安装包会被绑定），所以边缘手势只能由宿主代执行；
     * `access.perform` 只覆盖 7 个全局动作，坐标手势走这里。
     */
    const val API_ACCESS_GESTURE = "access.gesture"

    // 其它
    /** 宿主悬浮球显隐：`{visible}` */
    const val API_OVERLAY_VISIBLE = "overlay.setVisible"

    /** 打开宿主管理界面（BlackBox后台） */
    const val API_OPEN_BOX = "frame.openBox"

    /** 清单更新（宿主自己的口径；业务侧的热更新用 app.install，两者互不干扰） */
    const val API_UPDATE_CHECK = "update.check"
    const val API_UPDATE_APPLY = "update.apply"

    /**
     * 电池优化白名单：`{}` → `{whitelisted:bool, requested:bool}`。
     * 盒内业务不能自己申请（申请按 uid/包名走宿主），doze 下自动化任务的定时会飘。
     */
    const val API_POWER_WHITELIST = "power.whitelist"

    /** 读宿主剪贴板：`{}` → `{text}`（盒内 ClipboardManager 可能被伪造） */
    const val API_CLIPBOARD_GET = "frame.clipboard.get"

    /** 写宿主剪贴板：`{text, label?}` → `{ok}` */
    const val API_CLIPBOARD_SET = "frame.clipboard.set"

    /**
     * 宿主侧**命名空间 KV**（v3）：`{op:"get"|"set"|"del"|"list"|"clear", key?, value?}`
     *
     * 存在的理由只有一个：**业务要一份不受自己沙箱数据生命周期影响的小状态**。
     * 业务自己的 SharedPreferences 在「清数据安装 / 卸载重装」后会跟着消失，
     * 而有些记账（如"已确认装到哪个版本内容"、"迁移做过没有"）丢掉就会误判。
     *
     * 边界（宿主只提供介质，不理解内容）：
     *  · 命名空间 = 当前沙箱业务包名，业务之间互不可见；
     *  · 单值 ≤ 32KB、键数 ≤ 256，超了报 [ERR_BAD_PARAMS]（别把它当数据库）；
     *  · `get` → `{ok, exists, value}`；`set` → `{ok, bytes, entries}`；`list` → `{ok, keys}`。
     */
    const val API_FRAME_KV = "frame.kv"

    // ---------------- 错误码 ----------------
    const val ERR_UNSUPPORTED = "UNSUPPORTED_API"
    const val ERR_BAD_PARAMS = "BAD_PARAMS"
    const val ERR_NOT_IN_FRAME = "NOT_IN_FRAME"
    const val ERR_BUSY = "BUSY"
    const val ERR_IO = "IO"
    const val ERR_INSTALL_FAILED = "INSTALL_FAILED"
    const val ERR_PERMISSION = "PERMISSION"
    const val ERR_INTERNAL = "INTERNAL"

    /** 消费端侧超时（宿主长时间没回执）；由消费端自己判定，宿主不会主动发这个码 */
    const val ERR_TIMEOUT = "TIMEOUT"

    /** 用户/消费端取消（目前只有消费端侧计时放弃会用到） */
    const val ERR_CANCELED = "CANCELED"

    /**
     * 全部 api 的登记表（**唯一出处**：`capabilities.apis` 由它派生，改这里就够）。
     * `since` = 这条 api 从哪个协议版本开始有；新加的一律写 [VERSION]。
     */
    val CATALOG: List<ApiEntry> = listOf(
        // v1
        ApiEntry(API_CAPABILITIES, 1),
        ApiEntry(API_DEVICE_INFO, 1),
        ApiEntry(API_APP_INSTALL, 1),
        ApiEntry(API_APP_INSTALL_STATUS, 1),
        ApiEntry(API_APP_LIST, 1),
        ApiEntry(API_APP_LAUNCH, 1),
        ApiEntry(API_APP_UNINSTALL, 1),
        ApiEntry(API_PERMS_GET, 1),
        ApiEntry(API_PERMS_OPEN_SETTINGS, 1),
        ApiEntry(API_NOTIFY_STATE, 1),
        ApiEntry(API_NOTIFY_TOAST, 1),
        ApiEntry(API_NOTIFY_POST, 1),
        ApiEntry(API_ACCESS_STATE, 1),
        ApiEntry(API_ACCESS_PERFORM, 1),
        ApiEntry(API_OVERLAY_VISIBLE, 1),
        ApiEntry(API_OPEN_BOX, 1),
        ApiEntry(API_UPDATE_CHECK, 1),
        ApiEntry(API_UPDATE_APPLY, 1),
        // v2（2026-10-29 一批）
        ApiEntry(API_FRAME_PING, 2),
        ApiEntry(API_FRAME_LOG, 2),
        ApiEntry(API_APP_INFO, 2),
        ApiEntry(API_APP_CLEAR_DATA, 2),
        ApiEntry(API_PERMS_REQUEST, 2),
        ApiEntry(API_NOTIFY_CANCEL, 2),
        ApiEntry(API_ACCESS_GESTURE, 2),
        ApiEntry(API_POWER_WHITELIST, 2),
        ApiEntry(API_CLIPBOARD_GET, 2),
        ApiEntry(API_CLIPBOARD_SET, 2),
        // v3（2026-10-30）：通用底座 —— 业务可自服务的小状态 + 调用与结果对号入座
        ApiEntry(API_FRAME_KV, 3),
    )

    /** 只要名字的扁平视图（兼容/日志用；对外 capabilities 给 [CATALOG]） */
    val SUPPORTED: List<String> = CATALOG.map { it.name }

    /** 某个 api 从哪一版开始有（未登记 → 0） */
    fun sinceOf(api: String): Int = CATALOG.firstOrNull { it.name == api }?.since ?: 0
}
