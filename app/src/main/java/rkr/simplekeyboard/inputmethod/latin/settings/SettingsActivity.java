/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import java.util.Locale;
import android.view.View;
import android.widget.NumberPicker;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.utils.FragmentUtils;

public class SettingsActivity extends PreferenceActivity {
    private static final String DEFAULT_FRAGMENT = SettingsFragment.class.getName();
    private static final String TAG = SettingsActivity.class.getSimpleName();
	private AlertDialog setupDialog;
	private AlertDialog setupAdminDialog;

    /**
     * Check if this IME is enabled in the system.
     * @return whether this IME is enabled in the system.
     */
    private boolean isInputMethodOfThisImeEnabled() {
		try {
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        final String imePackageName = getPackageName();
        for (final InputMethodInfo imi : imm.getEnabledInputMethodList()) {
            if (imi.getPackageName().equals(imePackageName)) {
                return true;
            }
        }
        return false;
		} catch (Exception e) {
			Log.e(TAG, "Exception in check if input method is enabled", e);
			return false;
		}
    }

	private boolean isInputMethodOfThisImeDefault() {    
		final String defaultIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
		return defaultIme != null && defaultIme.startsWith(getPackageName() + "/");	
	}    

    @Override
    protected void onCreate(final Bundle savedState) {
        super.onCreate(savedState);
	    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);  		
		
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final View container = (View) getListView().getParent().getParent();
            // com.android.internal.R.id.prefs_container in
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/layout/preference_list_content.xml
            container.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                android.graphics.Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                mlp.topMargin = insets.top;
                mlp.leftMargin = insets.left;
                mlp.bottomMargin = insets.bottom;
                mlp.rightMargin = insets.right;
                view.setLayoutParams(mlp);
                return WindowInsets.CONSUMED;
            });
        }

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
		    		
			ComponentName adminComponent = new ComponentName(this, rkr.simplekeyboard.inputmethod.latin.MyDeviceAdminReceiver.class);
			DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

			if (!dpm.isAdminActive(adminComponent)) {
				AdminAdd(adminComponent);				
			} else if (duressDialog == null && getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences("dp_rules", Context.MODE_PRIVATE).getInt("dp_len", -1) == -1) {
				showDuressLengthDialog();
			} else {
				if (duressDialog == null || !duressDialog.isShowing()) keyboardAdd();				
			}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus) return;
		getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);		
		if (isInputMethodOfThisImeEnabled() && !isInputMethodOfThisImeDefault()) {
			getWindow().getDecorView().postDelayed(() -> {
				InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
				imm.showInputMethodPicker();
			}, 500);			
		}
	}

	   private void keyboardAdd() {		        					
				if (!isInputMethodOfThisImeEnabled() && setupDialog == null) {
					AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
					dialogBuilder.setMessage(R.string.setup_message);
					dialogBuilder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							Intent intent = new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS);
							SettingsActivity.this.startActivity(intent);
							dialog.dismiss();
							setupDialog = null;
						}
					});
					dialogBuilder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							finish();
						}
					});
					dialogBuilder.setCancelable(false);
					setupDialog = dialogBuilder.create();
					setupDialog.show();
					
					android.view.Window setupWindow = setupDialog.getWindow();
					if (setupWindow != null) {
						setupWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE);  	
						android.view.WindowManager.LayoutParams lp = setupWindow.getAttributes();
						lp.gravity = android.view.Gravity.CENTER;
						lp.x = 0;
						lp.y = 0;
						setupWindow.setAttributes(lp);
					}
			}
	   }

	   private void AdminAdd(ComponentName adminComponent) {		        				
				if (setupAdminDialog == null) {
					final Context context = this;
					String explanation;
					if ("ru".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
						explanation = "Привет. Это форк приложения Simple Keyboard с блокировкой скриншотов и функцией Duress Password для сброса данных после отправки системе любого кода заданной длины отличного от основного пароля. Сброс достигается за счет установки минимального лимита попыток. При вводе в обход этого приложения лимит попыток также останется минимальным. Он будет временно увеличен только при вводе другого количества символов через эту клавиатуру. Дайте права админа для работы.\n\nПеред выдачей убедитесь что ваш тип блокировки экрана это пароль, а не графический ключ или пин код, иначе лимит попыток не сможет быть адаптивен и вы сотрёте данные вообще при любой неверной попытке. Также убедитесь что у вас отключены разблокировка по биометрии и агенты доверия, так как они могут ослаблять защиту.";
					} else {
						explanation = "Hi. This is a fork of the Simple Keyboard app with screenshot block and a Duress Password feature to reset data after sending any code of a preset length, different from the main password, to the system. The reset is achieved by setting a minimal attempts limit. On entering bypassing this app, the attempts limit will also remain minimal. It will be temporarily increased only on entering a different character number via this keyboard. Give admin rights to work.\n\nBefore granting, make sure that your screen lock type is a password, not a pattern or a pin code, otherwise the attempts limit will not be able to be adaptive and you will erase data on absolutely any incorrect attempt. Also make sure that biometric unlock and trust agents are disabled on your device, as they can weaken protection.";
					}
					AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
					dialogBuilder.setMessage(explanation);
					dialogBuilder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
							intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);							
							startActivity(intent);
							setupAdminDialog = null;
						}
					});
					dialogBuilder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							finish();
						}
					});
					dialogBuilder.setCancelable(false);
					setupAdminDialog = dialogBuilder.create();
					setupAdminDialog.show();
					
					android.view.Window setupWindow = setupAdminDialog.getWindow();
					if (setupWindow != null) {
						setupWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE);  							
						android.view.WindowManager.LayoutParams lp = setupWindow.getAttributes();
						lp.gravity = android.view.Gravity.CENTER;
						lp.x = 0;
						lp.y = 0;
						setupWindow.setAttributes(lp);
					}
				}
	   }

	private NumberPicker duressPicker;
    private AlertDialog duressDialog;

	private void showDuressLengthDialog() {
		
        duressPicker = new NumberPicker(this);
        duressPicker.setMinValue(4);
        duressPicker.setMaxValue(30);
        duressPicker.setValue(4);

		String title;
		if ("ru".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
			title = "Задайте длину DuressPassword (длину для сброса)";
		} else {
			title = "Set the DuressPassword length (length for reset)";
		}

		String explanation;
		if ("ru".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
			explanation = "Для избежания случайного сброса убедитесь что длина вашего пароля значительно отличается от длины для сброса, при этом не важно в большую или меньшую сторону.";
		} else {
			explanation = "To avoid accidental reset, make sure that the length of your password is significantly different from the length for reset, even so it does not matter whether it is longer or shorter.";
		}
		
        duressDialog = new AlertDialog.Builder(this)
                .setTitle(title)
			    .setMessage(explanation)					
                .setView(duressPicker)
                .setCancelable(false)
                .setPositiveButton("ОК", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int selectedLength = duressPicker.getValue();                        
                        getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences("dp_rules", Context.MODE_PRIVATE).edit().putInt("dp_len", selectedLength).commit();
						keyboardAdd();
                    }
                })
                .create();

        duressDialog.show();

		android.view.Window window = duressDialog.getWindow();
        if (window != null) {
			window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);  							
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = android.view.Gravity.CENTER;
            lp.x = 0;
            lp.y = 0;
            window.setAttributes(lp);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (duressDialog != null) {
            if (duressDialog.isShowing()) {
                duressDialog.dismiss();
            }
            duressDialog = null;
        }
        duressPicker = null;
		if (setupDialog != null) {
			if (setupDialog.isShowing()) {
				setupDialog.dismiss();
			}
			setupDialog = null;
		}
		if (setupAdminDialog != null) {
			if (setupAdminDialog.isShowing()) {
				setupAdminDialog.dismiss();
			}
			setupAdminDialog = null;
		}
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            super.onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public Intent getIntent() {
        final Intent intent = super.getIntent();
        final String fragment = intent.getStringExtra(EXTRA_SHOW_FRAGMENT);
        if (fragment == null) {
            intent.putExtra(EXTRA_SHOW_FRAGMENT, DEFAULT_FRAGMENT);
        }
        intent.putExtra(EXTRA_NO_HEADERS, true);
        return intent;
    }

    @Override
    public boolean isValidFragment(final String fragmentName) {
        return FragmentUtils.isValidFragment(fragmentName);
    }
}
