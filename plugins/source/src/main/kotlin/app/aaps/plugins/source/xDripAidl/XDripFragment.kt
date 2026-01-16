/*
package app.aaps.plugins.source.xDripAidl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.aaps.core.ui.R // 👈 R 文件替换为新版
import app.aaps.plugins.source.xDripAidl.databinding.FragmentXdripAidlBinding // 👈 自动生成的 Binding 类

class XDripFragment : Fragment() {

    private var _binding: FragmentXdripAidlBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentXdripAidlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        updateStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // 避免内存泄漏
    }

    private fun setupViews() {
        binding.xdripStatus.text = getString(R.string.xdrip_aidl_initializing)
        binding.xdripLastData.text = getString(R.string.xdrip_aidl_no_data)
        // 可以在这里添加按钮点击事件等
    }

    private fun updateStatus() {
        // 更新UI状态，例如从 ViewModel 或插件获取数据
    }

    companion object {
        fun newInstance(): XDripFragment {
            return XDripFragment()
        }
    }
}
*/


/*
package app.aaps.plugins.source.xDripAidl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.aaps.core.ui.R // 👈 R 文件替换为新版
import plugins.source.src.main.res.xml.pref_xdrip_aidl
import plugins.source.src.main.res.layout.fragment_xdrip_aidl

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
*/
