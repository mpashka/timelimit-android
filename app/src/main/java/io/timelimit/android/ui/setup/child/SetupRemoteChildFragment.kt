/*
 * TimeLimit Copyright <C> 2019 - 2023 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.setup.child

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.timelimit.android.R
import io.timelimit.android.databinding.SetupRemoteChildFragmentBinding
import io.timelimit.android.extensions.setOnEnterListenr
import kotlinx.coroutines.launch

// @tag:family-join-qr
class SetupRemoteChildFragment : Fragment() {
    private val model: SetupRemoteChildViewModel by lazy {
        ViewModelProviders.of(this).get(SetupRemoteChildViewModel::class.java)
    }

    private val scanLoginCode = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { model.trySetup(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = SetupRemoteChildFragmentBinding.inflate(inflater, container, false)

        fun go() {
            model.trySetup(binding.editCode.text.toString())
        }

        binding.btnOk.setOnClickListener { go() }
        binding.editCode.setOnEnterListenr { go() }

        binding.scanCodeButton.setOnClickListener {
            scanLoginCode.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.DATA_MATRIX)
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt("")
            )
        }

        // @tag:family-join-google
        binding.googleJoinButton.setOnClickListener {
            lifecycleScope.launch {
                val url = model.startGoogleJoin()

                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (ex: ActivityNotFoundException) {
                    Snackbar.make(binding.root, R.string.setup_remote_child_google_no_browser, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        model.status.observe(this, Observer {
            status ->

            when (status) {
                SetupRemoteChildStatus.Idle -> binding.flipper.displayedChild = 0
                SetupRemoteChildStatus.Working -> binding.flipper.displayedChild = 1
                SetupRemoteChildStatus.CodeInvalid -> {
                    Snackbar.make(container!!, R.string.setup_remote_child_code_invalid, Snackbar.LENGTH_SHORT).show()

                    model.confirmError()
                }
                SetupRemoteChildStatus.NetworkError -> {
                    Snackbar.make(container!!, R.string.error_network, Snackbar.LENGTH_SHORT).show()

                    model.confirmError()
                }
                null -> {/* nothing to do */}
            }.let {  }
        })

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        model.resumeGoogleJoinPolling()
    }

    override fun onPause() {
        super.onPause()

        model.pauseGoogleJoinPolling()
    }
}
