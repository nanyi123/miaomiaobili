package com.a10miaomiao.bilimiao.page.bangumi

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.a10miaomiao.miao.binding.android.view.*
import cn.a10miaomiao.miao.binding.android.widget._text
import cn.a10miaomiao.miao.binding.android.widget._textColorResource
import com.a10miaomiao.bilimiao.MainNavGraph
import com.a10miaomiao.bilimiao.comm.*
import com.a10miaomiao.bilimiao.comm.delegate.player.BasePlayerDelegate
import com.a10miaomiao.bilimiao.comm.delegate.player.entity.PlayerParamInfo
import com.a10miaomiao.bilimiao.comm.delegate.player.model.BangumiPlayerSource
import com.a10miaomiao.bilimiao.comm.entity.bangumi.EpisodeInfo
import com.a10miaomiao.bilimiao.comm.mypage.MyPage
import com.a10miaomiao.bilimiao.comm.mypage.myPageConfig
import com.a10miaomiao.bilimiao.comm.recycler._miaoAdapter
import com.a10miaomiao.bilimiao.comm.recycler._miaoLayoutManage
import com.a10miaomiao.bilimiao.comm.recycler.miaoBindingItemUi
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.store.PlayerStore
import com.a10miaomiao.bilimiao.store.WindowStore
import com.chad.library.adapter.base.listener.OnItemClickListener
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dip
import splitties.views.backgroundColor
import splitties.views.dsl.core.*
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.horizontalPadding
import splitties.views.textColorResource
import splitties.views.verticalPadding

class BangumiPagesFragment : Fragment(), DIAware, MyPage {

    override val pageConfig = myPageConfig {
        title = "番剧剧集"
    }

    override val di: DI by lazyUiDi(ui = { ui })

//    private val viewModel by diViewModel<TemplateViewModel>(di)

    private val playerStore by instance<PlayerStore>()
    private val windowStore by instance<WindowStore>()
    private val basePlayerDelegate by instance<BasePlayerDelegate>()

    private val bangumi by lazy { requireArguments().getParcelable<BangumiPagesParam>(MainNavGraph.args.bangumi)!! }
    private val episodes by lazy { bangumi.episodes.toMutableList() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ui.parentView = container
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    private fun isPlaying(epid: String): Boolean {
        val info = playerStore.state
        return info.type == PlayerParamInfo.BANGUMI && info.epid == epid
    }

    val handleItemClick = OnItemClickListener { adapter, view, position ->
        val item = episodes[position]
        val playerSource = BangumiPlayerSource(
            sid = bangumi.sid,
            epid = item.ep_id,
            aid = item.aid,
            id = item.cid.toString(),
            title = item.index_title.ifBlank { item.index },
            coverUrl = item.cover,
            ownerId = "",
            ownerName = bangumi.title
        )
        basePlayerDelegate.openPlayer(playerSource)
    }

    val itemUi = miaoBindingItemUi<BangumiPagesParam.Episode> { item, index ->
        verticalLayout {
            setBackgroundResource(com.a10miaomiao.bilimiao.R.drawable.shape_corner)
            layoutParams = ViewGroup.MarginLayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dip(10)
            }
            horizontalPadding = dip(10)
            verticalPadding = dip(10)
            gravity = Gravity.LEFT

            val isSelect = isPlaying(item.ep_id)
            val isEmptyTitle = item.index_title.isEmpty()
            _isEnabled = !isSelect

            views {
                +textView {
                    textColorResource = com.a10miaomiao.bilimiao.R.color.text_black
                    _text = "第${item.index}集"
                    _textColorResource = if (isSelect) {
                        config.themeColorResource
                    } else {
                        com.a10miaomiao.bilimiao.R.color.text_black
                    }
                }

                +textView {
                    textColorResource = com.a10miaomiao.bilimiao.R.color.text_black
                    textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.LEFT
                    textAlignment = TextView.TEXT_ALIGNMENT_TEXT_START

                    _show = !isEmptyTitle
                    _text = item.index_title
                    _textColorResource = if (isSelect) {
                        config.themeColorResource
                    } else {
                        com.a10miaomiao.bilimiao.R.color.text_black
                    }
                }..lParams(matchParent, wrapContent) {
                    topMargin = dip(5)
                }
            }
        }
    }

    val ui = miaoBindingUi {
        connectStore(viewLifecycleOwner, windowStore)
        connectStore(viewLifecycleOwner, playerStore)
        val contentInsets = windowStore.getContentInsets(parentView)

        recyclerView {
            backgroundColor = config.windowBackgroundColor
            layoutParams = ViewGroup.MarginLayoutParams(matchParent, matchParent).apply {
                _topMargin = contentInsets.top
            }
            _bottomPadding = contentInsets.bottom
            _leftPadding = contentInsets.left + config.pagePadding
            _rightPadding = contentInsets.right + config.pagePadding

            _miaoLayoutManage(
                LinearLayoutManager(requireContext())
            )

            _miaoAdapter(
                items = episodes,
                itemUi = itemUi,
                depsAry = arrayOf(playerStore.state.cid)
            ) {
                setOnItemClickListener(handleItemClick)
            }
        }
    }

}