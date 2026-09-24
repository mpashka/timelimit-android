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
package io.timelimit.android.ui.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.data.model.User
import io.timelimit.android.databinding.NewLoginFragmentBinding
import io.timelimit.android.extensions.setOnEnterListenr
import io.timelimit.android.ui.MainActivity
import io.timelimit.android.ui.extension.openNextWizardScreen
import io.timelimit.android.ui.extension.openPreviousWizardScreen
import io.timelimit.android.ui.main.ActivityViewModelHolder
import io.timelimit.android.ui.main.getActivityViewModel
import io.timelimit.android.ui.manage.parent.key.MissingBinaryEyeDialogFragment
import io.timelimit.android.ui.manage.parent.key.ScanBarcode
import io.timelimit.android.ui.manage.parent.key.ScannedKey
import io.timelimit.android.ui.view.KeyboardViewListener

class NewLoginFragment: DialogFragment() {
    companion object {
        const val SHOW_ON_LOCKSCREEN = "showOnLockscreen"
        private const val KEEP_SIGNED_IN = "keepSignedIn"

        // @tag:new-ui
        fun keepingSignedIn() = NewLoginFragment().apply {
            arguments = Bundle().apply { putBoolean(KEEP_SIGNED_IN, true) }
        }

        fun newInstance(showOnLockscreen: Boolean) = NewLoginFragment().apply {
            arguments = Bundle().apply {
                putBoolean(SHOW_ON_LOCKSCREEN, showOnLockscreen)
            }
        }

        private const val SELECTED_USER_ID = "selectedUserId"
        private const val USER_LIST = 0
        private const val PARENT_AUTH = 1
        private const val CHILD_MISSING_PASSWORD = 2
        private const val CHILD_ALREADY_CURRENT_USER = 3
        private const val CHILD_AUTH = 4
        private const val CHILD_LOGIN_REQUIRES_PREMIUM = 5
        private const val UNVERIFIED_TIME = 6
        private const val PARENT_LOGIN_BLOCKED = 7
        private const val WAITING_FOR_SYNC = 8
    }

    private val model: LoginDialogFragmentModel by viewModels()
    private var keepSignedInApplied = false

    private val inputMethodManager: InputMethodManager by lazy {
        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    private val activityModelHolder get() = requireActivity() as ActivityViewModelHolder

    private val scanLoginCode = registerForActivityResult(ScanBarcode()) { barcode ->
        barcode ?: return@registerForActivityResult

        ScannedKey.tryDecode(barcode).let { key ->
            if (key == null) Toast.makeText(requireContext(), R.string.manage_user_key_invalid, Toast.LENGTH_SHORT).show()
            else tryCodeLogin(key)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState?.containsKey(SELECTED_USER_ID) == true) {
            model.selectedUserId.value = savedInstanceState.getString(SELECTED_USER_ID)
        }

        if (savedInstanceState == null) {
            model.tryDefaultLogin(getActivityViewModel(requireActivity()))
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) = object: BottomSheetDialog(requireContext(), theme) {
        init {
            onBackPressedDispatcher.addCallback(object: OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!model.goBack()) dismissAllowingStateLoss()
                }
            })
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                if (arguments?.getBoolean(SHOW_ON_LOCKSCREEN, false) == true) {
                    window!!.addFlags(
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        model.selectedUserId.value?.let { outState.putString(SELECTED_USER_ID, it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = NewLoginFragmentBinding.inflate(inflater, container, false)

        val adapter = LoginUserAdapter()

        adapter.listener = object: LoginUserAdapterListener {
            override fun onUserClicked(user: User) {
                // reset parent password view
                binding.enterPassword.checkAssignMyself.isChecked = false
                binding.enterPassword.checkDontAskAgain.isChecked = false
                keepSignedInApplied = false
                binding.enterPassword.password.setText("")

                // go to the next step
                model.startSignIn(user)
            }

            override fun onScanCodeRequested() {
                try {
                    scanLoginCode.launch(null)
                } catch (ex: ActivityNotFoundException) {
                    MissingBinaryEyeDialogFragment.newInstance().show(parentFragmentManager)
                }
            }
        }

        binding.userList.recycler.adapter = adapter
        binding.userList.recycler.layoutManager = LinearLayoutManager(context)

        binding.enterPassword.apply {
            showKeyboardButton.setOnClickListener {
                showCustomKeyboard = !showCustomKeyboard

                if (showCustomKeyboard) {
                    inputMethodManager.hideSoftInputFromWindow(password.windowToken, 0)
                } else {
                    inputMethodManager.showSoftInput(password, 0)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    password.showSoftInputOnFocus = !showCustomKeyboard
                }
            }

            fun go() {
                model.tryParentLogin(
                        password = password.text.toString(),
                        keepSignedIn = checkDontAskAgain.isChecked,
                        model = getActivityViewModel(requireActivity()),
                        setAsDeviceUser = checkAssignMyself.isChecked
                )
            }

            keyboard.listener = object: KeyboardViewListener {
                override fun onItemClicked(content: String) {
                    val start = Math.max(password.selectionStart, 0)
                    val end = Math.max(password.selectionEnd, 0)

                    password.text.replace(Math.min(start, end), Math.max(start, end), content, 0, content.length)
                }

                override fun onGoClicked() {
                    go()
                }
            }

            password.setOnEnterListenr { go() }

            forgotPasswordButton.setOnClickListener {
                startActivity(
                        Intent(requireContext(), MainActivity::class.java)
                                .setAction(MainActivity.ACTION_USER_OPTIONS)
                                .putExtra(MainActivity.EXTRA_USER_ID, model.selectedUserId.value)
                )

                dismissAllowingStateLoss()
            }
        }

        binding.childPassword.apply {
            password.setOnEnterListenr {
                model.tryChildLogin(
                        password = password.text.toString()
                )
            }
        }

        model.status.observe(viewLifecycleOwner, Observer { status ->
            when (status) {
                LoginDialogDone -> {
                    dismissAllowingStateLoss()
                }
                is UserListLoginDialogStatus -> {
                    binding.switcher.openPreviousWizardScreen(USER_LIST)

                    val users = status.usersToShow.map { LoginUserAdapterUser(it) }

                    adapter.data =  if (status.showScanOption)
                        users + LoginUserAdapterScan
                    else
                        users

                    Threads.mainThreadHandler.post { binding.userList.recycler.requestFocus() }

                    null
                }
                is ParentUserLogin -> {
                    binding.switcher.openNextWizardScreen(PARENT_AUTH)

                    binding.enterPassword.password.isEnabled = !status.isCheckingPassword

                    if (!binding.enterPassword.showCustomKeyboard) {
                        binding.enterPassword.password.requestFocus()
                        inputMethodManager.showSoftInput(binding.enterPassword.password, 0)
                    }

                    binding.enterPassword.showKeepLoggedInOption = status.isConnectedMode
                    binding.enterPassword.forgotPasswordButton.visibility = if (status.showForgotPassword && activityModelHolder.showPasswordRecovery) View.VISIBLE else View.GONE

                    if (status.isAlreadyCurrentDeviceUser) {
                        binding.enterPassword.canNotKeepLoggedIn = false

                        binding.enterPassword.checkAssignMyself.setOnCheckedChangeListener { _, _ ->  }
                        binding.enterPassword.checkAssignMyself.isEnabled = false
                        binding.enterPassword.checkAssignMyself.isChecked = true
                    } else {
                        binding.enterPassword.checkAssignMyself.isEnabled = true

                        fun bindCanNotKeepLoggedIn() {
                            binding.enterPassword.canNotKeepLoggedIn = !binding.enterPassword.checkAssignMyself.isChecked
                        }

                        bindCanNotKeepLoggedIn()
                        binding.enterPassword.checkAssignMyself.setOnCheckedChangeListener { _, _ -> bindCanNotKeepLoggedIn() }
                    }

                    // the parent screen of the new interface asks for a sign-in that is kept on this device;
                    // the password step can be reached without a tap on a user, so the ticks are set here
                    // @tag:new-ui
                    if (!keepSignedInApplied && arguments?.getBoolean(KEEP_SIGNED_IN, false) == true && status.isConnectedMode) {
                        keepSignedInApplied = true
                        if (binding.enterPassword.checkAssignMyself.isEnabled) binding.enterPassword.checkAssignMyself.isChecked = true
                        binding.enterPassword.canNotKeepLoggedIn = false
                        binding.enterPassword.executePendingBindings()
                        binding.enterPassword.checkDontAskAgain.isChecked = true
                    }

                    if (status.wasPasswordWrong) {
                        Toast.makeText(requireContext(), R.string.login_snackbar_wrong, Toast.LENGTH_SHORT).show()
                        binding.enterPassword.password.setText("")

                        model.resetPasswordWrong()
                    }

                    null
                }
                ParentUserLoginMissingTrustedTime -> {
                    binding.switcher.openNextWizardScreen(UNVERIFIED_TIME)

                    null
                }
                is CanNotSignInChildHasNoPassword -> {
                    binding.switcher.openNextWizardScreen(CHILD_MISSING_PASSWORD)

                    binding.childWithoutPassword.childName = status.childName

                    null
                }
                is ChildAlreadyDeviceUser -> {
                    binding.switcher.openNextWizardScreen(CHILD_ALREADY_CURRENT_USER)

                    null
                }
                is ChildUserLogin -> {
                    binding.switcher.openNextWizardScreen(CHILD_AUTH)

                    binding.childPassword.password.requestFocus()
                    inputMethodManager.showSoftInput(binding.childPassword.password, 0)

                    binding.childPassword.password.isEnabled = !status.isCheckingPassword

                    if (status.wasPasswordWrong) {
                        Toast.makeText(requireContext(), R.string.login_snackbar_wrong, Toast.LENGTH_SHORT).show()
                        binding.childPassword.password.setText("")

                        model.resetPasswordWrong()
                    }

                    null
                }
                ChildLoginRequiresPremiumStatus -> {
                    binding.switcher.openNextWizardScreen(CHILD_LOGIN_REQUIRES_PREMIUM)

                    null
                }
                is ParentUserLoginBlockedByCategory -> {
                    binding.switcher.openNextWizardScreen(PARENT_LOGIN_BLOCKED)

                    binding.parentLoginBlocked.categoryTitle = status.categoryTitle
                    binding.parentLoginBlocked.reason = LoginDialogFragmentModel.formatBlockingReasonForLimitLoginCategory(status.reason, requireContext())

                    null
                }
                ParentUserLoginWaitingForSync -> {
                    binding.switcher.openNextWizardScreen(WAITING_FOR_SYNC)

                    null
                }
            }.let { /* require handling all cases */ }
        })

        return binding.root
    }

    fun tryCodeLogin(code: ScannedKey) {
        model.tryCodeLogin(code, getActivityViewModel(requireActivity()))
    }
}
