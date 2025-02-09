package com.a10miaomiao.bilimiao.comm.delegate.player

import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.NetworkOnMainThreadException
import android.preference.PreferenceManager
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.a10miaomiao.bilimiao.comm.delegate.helper.PicInPicHelper
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerSourceInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.model.BangumiPlayerSource
import com.a10miaomiao.bilimiao.comm.exception.AreaLimitException
import com.a10miaomiao.bilimiao.comm.exception.DabianException
import com.a10miaomiao.bilimiao.comm.delegate.player.model.BasePlayerSource
import com.a10miaomiao.bilimiao.comm.delegate.player.model.VideoPlayerSource
import com.a10miaomiao.bilimiao.comm.delegate.theme.ThemeDelegate
import com.a10miaomiao.bilimiao.comm.entity.player.SubtitleJsonInfo
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.gson
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.DebugMiao
import com.a10miaomiao.bilimiao.comm.utils.UrlUtil
import com.a10miaomiao.bilimiao.comm.view.network
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.page.setting.VideoSettingFragment
import com.a10miaomiao.bilimiao.service.PlayerService
import com.a10miaomiao.bilimiao.store.PlayerStore
import com.a10miaomiao.bilimiao.widget.comm.getScaffoldView
import com.a10miaomiao.bilimiao.widget.player.DanmakuVideoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.toast.toast
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager
import tv.danmaku.ijk.media.exo2.ExoMediaSourceInterceptListener
import tv.danmaku.ijk.media.exo2.ExoSourceManager
import java.io.File
import java.net.UnknownHostException


class PlayerDelegate2(
    private var activity: AppCompatActivity,
    override val di: DI,
) : BasePlayerDelegate, DIAware, ExoMediaSourceInterceptListener {

    val DEFAULT_REFERER = "https://www.bilibili.com/"
    val DEFAULT_USER_AGENT = "Bilibili Freedoooooom/MarkII"

    val views by lazy { PlayerViews(activity) }
    val controller by lazy {
        PlayerController(activity, this, di)
    }
    val errorMessageBoxController by lazy {
        ErrorMessageBoxController(activity, this, di)
    }
    val areaLimitBoxController by lazy {
        AreaLimitBoxController(activity, this, di)
    }
    val scaffoldApp by lazy { activity.getScaffoldView() }

    var picInPicHelper: PicInPicHelper? = null
        private set

    private val userStore by instance<UserStore>()
    private val playerStore by instance<PlayerStore>()
    private val themeDelegate by instance<ThemeDelegate>()

    var playerSourceInfo: PlayerSourceInfo? = null

    // 未登陆：48[480P 清晰]及以下
    // 已登陆无大会员：80[1080P 高清]及以下
    // 大会员：无限制
    val MAX_QUALITY_NOT_LOGIN = 48 // 48[480P 清晰]
    val MAX_QUALITY_NOT_VIP = 80 // 80[1080P 高清]
    var quality = 64 // 默认[高清 720P]懒得做记忆功能，先不弄
    var fnval = 4048 // 视频格式: 0:flv,1:mp4,4048:dash

    var speed = 1f // 播放速度
    private var lastPosition = 0L
    private val playerCoroutineScope = PlayerCoroutineScope()
    private var playerSource: BasePlayerSource? = null
        set(value) {
            field = value
            if (value != null) {
                playerStore.setPlayerSource(value)
            } else {
                playerStore.clearPlayerInfo()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
//        PlayerFactory.setPlayManager(MyIjkPlayerManager::class.java)
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java) //EXO模式
        ExoSourceManager.setExoMediaSourceInterceptListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            picInPicHelper = PicInPicHelper(activity, views.videoPlayer)
        }
        controller.initController()
        controller.initDanmakuContext()
        views.videoPlayer.subtitleLoader = this::loadSubtitleData

        // 主题监听
        themeDelegate.observeTheme(activity, Observer {
            val themeColor = activity.config.themeColor
            views.videoPlayer.updateThemeColor(activity, themeColor)
        })
    }

    override fun onResume() {

    }

    override fun onPause() {

    }

    override fun onStart() {
        playerCoroutineScope.onStart()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.getBoolean("player_background", true)
            && !views.videoPlayer.isInPlayingState
        ) {
            views.videoPlayer.onVideoResume()
        }
    }

    override fun onStop() {
        playerCoroutineScope.onStop()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        if (!prefs.getBoolean("player_background", true)
            && views.videoPlayer.isInPlayingState
        ) {
//            lastPosition = mPlayer.currentPosition
            views.videoPlayer.onVideoPause()
        }
    }

    override fun onDestroy() {

    }

    override fun onBackPressed(): Boolean {
        if (views.videoPlayer.isLock) {
            return true
        }
        if (scaffoldApp.fullScreenPlayer) {
            controller.onBackClick()
            return true
        }
        return false
    }

    override fun getMediaSource(
        dataSource: String,
        preview: Boolean,
        cacheEnable: Boolean,
        isLooping: Boolean,
        cacheDir: File?
    ): MediaSource? {
        val dataSourceArr = dataSource.split("\n")
        when (dataSourceArr[0]) {
            "[local-merging]" -> {
                // 本地音视频分离
                val localSourceFactory = DefaultDataSource.Factory(activity)
                val videoMedia = MediaItem.fromUri(dataSourceArr[1])
                val audioMedia = MediaItem.fromUri(dataSourceArr[2])
                return MergingMediaSource(
                    ProgressiveMediaSource.Factory(localSourceFactory)
                        .createMediaSource(videoMedia),
                    ProgressiveMediaSource.Factory(localSourceFactory)
                        .createMediaSource(audioMedia)
                )
            }
            "[concatenating]" -> {
                // 视频拼接
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                val header = getDefaultRequestProperties()
                dataSourceFactory.setUserAgent(DEFAULT_USER_AGENT)
                dataSourceFactory.setDefaultRequestProperties(header)
                return ConcatenatingMediaSource().apply {
                    for (i in 1 until dataSourceArr.size) {
                        addMediaSource(
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(dataSourceArr[i]))
                        )
                    }
                }
            }
            "[dash-mpd]" -> {
                // Create a data source factory.
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                val header = getDefaultRequestProperties()
                dataSourceFactory.setUserAgent(DEFAULT_USER_AGENT)
                dataSourceFactory.setDefaultRequestProperties(header)
                // Create a DASH media source pointing to a DASH manifest uri.
                val uri = Uri.parse(dataSourceArr[1])
                val dashStr = dataSourceArr[2]
                val dashManifest =
                    DashManifestParser().parse(uri, dashStr.toByteArray().inputStream())
                val mediaSource = DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(dashManifest)
//                    mediaSource.prepareSource()
                return mediaSource
            }
        }
        return null
    }

    override fun getHttpDataSourceFactory(
        userAgent: String,
        listener: TransferListener?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        mapHeadData: Map<String, String>,
        allowCrossProtocolRedirects: Boolean
    ): DataSource.Factory? {
        return null
    }

    private fun getDefaultRequestProperties(): Map<String, String> {
        val header = HashMap<String, String>()
        if (playerSource is VideoPlayerSource) {
            header["Referer"] = DEFAULT_REFERER
        }
        header["User-Agent"] = DEFAULT_USER_AGENT
        return header
    }

    private fun historyReport() {
        playerCoroutineScope.launch(Dispatchers.IO) {
            playerSource?.historyReport(views.videoPlayer.currentPositionWhenPlaying / 1000)
        }
    }

    private fun setThumbImageView(coverUrl: String) {
        views.videoPlayer.thumbImageView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            network(coverUrl)
        }
    }

    fun changedSpeed(newSpeed: Float) {
        if (speed != newSpeed) {
            lastPosition = views.videoPlayer.currentPositionWhenPlaying
            speed = newSpeed
            views.videoPlayer.setSpeed(speed, true)
            toast("已切换到${speed}倍速")
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            prefs.edit().putFloat("player_speed", newSpeed).apply()
        }
    }

    fun changedQuality(newQuality: Int) {
        if (quality != newQuality) {
            lastPosition = views.videoPlayer.currentPositionWhenPlaying
            quality = newQuality
            loadPlayerSource(
                isChangedQuality = true
            )
            toast("正在切换清晰度")
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            prefs.edit().putInt("player_quality", newQuality).apply()
        }
    }

    private fun loadPlayerSource(
        isChangedQuality: Boolean = false
    ) {
        val source = playerSource ?: return
        playerCoroutineScope.launch(Dispatchers.IO) {
            try {
                val danmukuParser = source.getDanmakuParser()
                val sourceInfo = source.getPlayerUrl(quality, fnval)
                withContext(Dispatchers.Main) {
                    // 设置通知栏控制器
                    PlayerService.selfInstance?.setPlayingInfo(
                        source.title,
                        source.ownerName,
                        source.coverUrl,
                        sourceInfo.duration
                    )
                    views.videoPlayer.releaseDanmaku()
                    views.videoPlayer.danmakuParser = danmukuParser
                    val header = getDefaultRequestProperties()
                    views.videoPlayer.setUp(
                        sourceInfo.url,
                        false,
                        null,
                        header,
                        source.title
                    )
                    if (lastPosition > 0L) {
                        views.videoPlayer.seekOnStart = lastPosition
                        lastPosition = 0L
                    } else {
                        views.videoPlayer.seekOnStart = 0
                    }
                    views.videoPlayer.startPlayLogic()

                    historyReport()

                    if (isChangedQuality) {
                        if (sourceInfo.quality == quality) {
                            toast("已切换至【${sourceInfo.description}】")
                        } else {
                            toast("清晰度切换失败")
                        }
                    } else {
                        views.videoPlayer.subtitleSourceList = withContext(Dispatchers.IO) {
                            source.getSubtitles()
                        }
                    }
                }
                quality = sourceInfo.quality
                playerSourceInfo = sourceInfo

            } catch (e: DabianException) {
                withContext(Dispatchers.Main) {
                    errorMessageBoxController.show("少儿不宜，禁止观看", canRetry = false)
                }
            }  catch (e: AreaLimitException) {
                withContext(Dispatchers.Main) {
                    (playerSource as? BangumiPlayerSource)?.let {
                        areaLimitBoxController.show(it)
                    } ?: errorMessageBoxController.show("抱歉你所在的地区不可观看！")
                }
            } catch (e: UnknownHostException) {
                withContext(Dispatchers.Main) {
                    errorMessageBoxController.show("无法连接到御坂网络")
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorMessageBoxController.show(e.message ?: e.toString())
                }
            }
        }
    }


    /**
     * 加载字幕数据
     */
    private fun loadSubtitleData(subtitleUrl: String) {
        if (subtitleUrl.isBlank()) {
            return
        }
        playerCoroutineScope.launch(Dispatchers.IO) {
            try {
                val res = MiaoHttp.request {
                    url = UrlUtil.autoHttps(subtitleUrl)
                }.awaitCall().gson<SubtitleJsonInfo>()
                views.videoPlayer.subtitleBody = res.body.map {
                    DanmakuVideoPlayer.SubtitleItemInfo(
                        from = (it.from * 1000).toLong(),
                        to = (it.to * 1000).toLong(),
                        content = it.content,
                    )
                }
            } catch (e: Throwable) {
                // 避免解析信息出错导致崩溃
                // showErrorLayout(e.message.toString())
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast(e.message.toString())
                }
            }
        }
    }

    /**
     * 记录播放位置
     */
    fun reloadPlayer () {
        lastPosition = views.videoPlayer.currentPositionWhenPlaying
        loadPlayerSource()
    }

    override fun openPlayer(source: BasePlayerSource) {
        errorMessageBoxController.hide()
        areaLimitBoxController.hide()


        historyReport()
        views.videoPlayer.release()
        lastPosition = 0L

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        fnval =
            prefs.getString(VideoSettingFragment.PLAYER_FNVAL, VideoSettingFragment.FNVAL_DASH)!!
                .toInt()
        quality = prefs.getInt("player_quality", 64).let {
            if (!userStore.isLogin() && it > MAX_QUALITY_NOT_LOGIN) {
                // 未登陆：48[480P 清晰]及以下
                return@let MAX_QUALITY_NOT_LOGIN
            } else if (!userStore.isVip() && it > MAX_QUALITY_NOT_VIP) {
                // 无大会员：80[1080P 高清]及以下
                return@let MAX_QUALITY_NOT_VIP
            }
            return@let it
        }
        speed = prefs.getFloat("player_speed", 1f)
        lastPosition = 0L
        scaffoldApp.showPlayer = true
        playerCoroutineScope.onStart()
        playerSource = source
//        setThumbImageView(source.coverUrl)
        views.videoPlayer.setSpeed(speed, true)
        loadPlayerSource()
        // 播放倍速提示
        if (speed != 1f) {
            toast("注意，当前为${speed}倍速")
        }

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 播放器是否默认全屏播放
        controller.checkIsPlayerDefaultFull()
    }

    override fun closePlayer() {
        scaffoldApp.showPlayer = false
        playerCoroutineScope.onStop()
        historyReport()
        playerSource = null

        views.videoPlayer.release()
        lastPosition = 0L

        // 设置通知栏控制器
        PlayerService.selfInstance?.clearPlayingInfo()

        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun isPlaying(): Boolean {
        return views.videoPlayer.isInPlayingState
    }

    override fun setWindowInsets(left: Int, top: Int, right: Int, bottom: Int) {
        views.videoPlayer.setWindowInsets(left, top, right, bottom)
    }

    override fun updateDanmukuSetting() {
        controller.initDanmakuContext()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration?
    ) {
        picInPicHelper?.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) { // 进入画中画模式，则隐藏其它控件
            // 隐藏视频控制器
            views.videoPlayer.hideController()
            //
            views.videoPlayer.isPicInPicMode = true
            // 视频组件全屏
            scaffoldApp.fullScreenPlayer = true
            // 调整弹幕样式，调小字体，限制行数
            controller.initDanmakuContext()
        } else {
            scaffoldApp.fullScreenPlayer =
                views.videoPlayer.mode == DanmakuVideoPlayer.PlayerMode.FULL
            views.videoPlayer.isPicInPicMode = false
            controller.initDanmakuContext()
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        controller.updatePlayerMode(newConfig)
    }

}