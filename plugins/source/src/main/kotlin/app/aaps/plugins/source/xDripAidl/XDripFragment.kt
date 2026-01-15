package app.aaps.plugins.source.xDripAidl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.aaps.core.ui.R // 👈 R 文件替换为新版
import kotlinx.android.synthetic.main.fragment_xdrip_aidl.* // ⚠️ 语法糖保留，但需确保布局文件存在

class XDripFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_xdrip_aidl, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupViews()
        updateStatus()
    }

    private fun setupViews() {
        xdrip_status.text = getString(R.string.xdrip_aidl_initializing)
        xdrip_last_data.text = getString(R.string.xdrip_aidl_no_data)
        
        // 可以在这里添加按钮点击事件等
    }

    private fun updateStatus() {
        // 更新UI状态
    }

    companion object {
        fun newInstance(): XDripFragment {
            return XDripFragment()
        }
    }
}
