package com.soomla.store.hooks;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.facebook.FacebookRequestError;
import com.facebook.HttpMethod;
import com.facebook.Request;
import com.facebook.RequestAsyncTask;
import com.facebook.Response;
import com.facebook.Session;
import com.facebook.SessionState;
import com.soomla.store.HighwayConfig;
import com.soomla.store.StoreInventory;
import com.soomla.store.StoreUtils;
import com.soomla.store.data.StorageManager;
import com.soomla.store.data.StoreInfo;
import com.soomla.store.domain.VirtualItem;
import com.soomla.store.exceptions.VirtualItemNotFoundException;
import com.soomla.store.storefront.StorefrontController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class SoomlaFacebook implements IStoreHook {

    public SoomlaFacebook() {
    }

    @Override
    public void initWithConfig(HashMap<String, Object> config) {
    }

    @Override
    public void restart() {
    }

    @Override
    public void runHookAction(String action, HashMap<String, Object> params) {
        if (action.equals("openToLike")) {
            String itemId = params.get("itemId").toString();
            String pageName = params.get("pageName").toString();
            int amountToGive = (Integer)params.get("amountToGive");
            Activity activity = (Activity)params.get("activity");
            openToLikePage(pageName, itemId, amountToGive, activity);
        }
    }

    private static String keyLikeCompletedDict(String pageName) {
        return "soomla.store.hook.facebook." + pageName;
    }

    public static void openToLikePage(String pageName, String itemId, int amountToGive, Activity activity) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.facebook.com/" + pageName));
        activity.startActivity(browserIntent);

        StorageManager.getKeyValueStorage().setValue("completed", keyLikeCompletedDict(pageName));
        try {
            StoreInventory.giveVirtualItem(itemId, amountToGive);
        } catch (VirtualItemNotFoundException e) {
            StoreUtils.LogError(TAG, "Couldn't find itemId: " + itemId);
        }

        if (HighwayConfig.SOOMLA_SF) {
            StorefrontController.getInstance().sendMessageToJS("{ \"type\":\"facebook\", \"success\":true, \"amount\":" + amountToGive + ", \"itemId\":\" " + itemId + "\" }");
        }
    }

    public static void shareLink(String link, String message, String itemId, int amountToGive, final Activity activity) {
        Intent intent = new Intent(activity.getApplicationContext(), SoomlaFacebookActivity.class);
        Bundle b = new Bundle();
        b.putInt(SoomlaFacebookActivity.ACTN, ACTION_POST_LINK);
        b.putString("link", link);
        b.putString("message", message);
        b.putString("itemId", itemId);
        b.putInt("amount", amountToGive);
        intent.putExtra(SoomlaFacebookActivity.PRMS, b);
        activity.startActivity(intent);
    }

    public static void shareText(String message, String itemId, int amountToGive, final Activity activity) {
        Intent intent = new Intent(activity.getApplicationContext(), SoomlaFacebookActivity.class);
        Bundle b = new Bundle();
        b.putInt(SoomlaFacebookActivity.ACTN, ACTION_POST_TEXT);
        b.putString("message", message);
        b.putString("itemId", itemId);
        b.putInt("amount", amountToGive);
        intent.putExtra(SoomlaFacebookActivity.PRMS, b);
        activity.startActivity(intent);
    }

    public static void shareImage(String imageUrl, String message, String itemId, int amountToGive, final Activity activity) {
        Intent intent = new Intent(activity.getApplicationContext(), SoomlaFacebookActivity.class);
        Bundle b = new Bundle();
        b.putInt(SoomlaFacebookActivity.ACTN, ACTION_POST_TEXT);
        b.putString("imageUrl", imageUrl);
        b.putString("message", message);
        b.putString("itemId", itemId);
        b.putInt("amount", amountToGive);
        intent.putExtra(SoomlaFacebookActivity.PRMS, b);
        activity.startActivity(intent);
    }

    public static class SoomlaFacebookActivity extends Activity {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mBundle = getIntent().getBundleExtra(PRMS);
            if (mBundle == null) throw new RuntimeException("Invalid params bundle, something went wrong!");
            mAction = mBundle.getInt(ACTN, -1);
            if (mAction == -1) throw new UnsupportedOperationException("Please specify action to preform when opening " + this.getLocalClassName());
        }

        @Override
        public void onResume() {
            super.onResume();
            Session session = Session.getActiveSession();
            if (session != null && (session.isOpened() || session.isClosed()) ) {
                onSessionStateChange(session, session.getState(), null);
            } else {
                Session.openActiveSession(this, true, callback);
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            Session.getActiveSession().onActivityResult(this, requestCode, resultCode, data);
        }

        private Session.StatusCallback callback = new Session.StatusCallback() {
            @Override
            public void call(Session session, SessionState state, Exception exception) {
                onSessionStateChange(session, state, exception);
            }
        };

        private void onSessionStateChange(Session session, SessionState sessionState, Exception exception) {
            if (session.isOpened()) {
                Log.d(TAG, "Opened Facebook connection");
                if (mAction == ACTION_POST_TEXT) {
                    ActionPostText();
                } else if (mAction == ACTION_POST_LINK) {
                    ActionPostLink();
                } else if (mAction == ACTION_POST_IMAGE) {
                    ActionPostImage();
                } else {
                    throw new UnsupportedOperationException("Unknown action: '" + mAction + "'");
                }
            } else {
                StoreUtils.LogError(TAG, "Facebook session connection is closed");
            }
        }

        /** Actions: */
        public void ActionPostText() {
            Session session = Session.getActiveSession();
            if (session != null){
                // Check for publish permissions
                if (!canPost(session)) return;

                String message = mBundle.getString("message");

                Bundle postParams = new Bundle();
                postParams.putString("message", message);

                Request request = new Request(session, "me/feed", postParams,
                        HttpMethod.POST, handleCompletedAction);
                RequestAsyncTask task = new RequestAsyncTask(request);
                task.execute();
            }
        }

        public void ActionPostLink() {
            Session session = Session.getActiveSession();
            if (session != null){
                if (!canPost(session)) return;

                String link = mBundle.getString("link");
                String message = mBundle.getString("message");

                Bundle postParams = new Bundle();
                postParams.putString("message", message);
                postParams.putString("link", link);

                Request request = new Request(session, "me/feed", postParams,
                        HttpMethod.POST, handleCompletedAction);
                RequestAsyncTask task = new RequestAsyncTask(request);
                task.execute();
            }
        }

        public void ActionPostImage() {
            Session session = Session.getActiveSession();
            if (session != null){
                if (!canPost(session)) return;

                String image = mBundle.getString("image");
                String message = mBundle.getString("message");

                Bundle postParams = new Bundle();
                postParams.putString("message", message);
                postParams.putString("link", image);

                Request request = new Request(session, "me/feed", postParams,
                        HttpMethod.POST, handleCompletedAction);
                RequestAsyncTask task = new RequestAsyncTask(request);
                task.execute();
            }
        }

        /** Extra Facebook stuff */

        private boolean canPost(Session session) {
            List<String> permissions = session.getPermissions();
            boolean canPost = true;
            for (String string : PERMISSIONS) {
                if (!permissions.contains(string)) {
                    canPost = false;
                }
            }
            canPost = canPost && true;

            if (!canPost) {
                Session.NewPermissionsRequest newPermissionsRequest =
                        new Session.NewPermissionsRequest(this, PERMISSIONS);
                session.requestNewPublishPermissions(newPermissionsRequest);
                return false;
            }
            return true;
        }

        private Request.Callback handleCompletedAction = new Request.Callback() {
            public void onCompleted(Response response) {
                FacebookRequestError error = response.getError();
                if (error != null) {
                    Toast.makeText(SoomlaFacebookActivity.this,
                            error.getErrorMessage(),
                            Toast.LENGTH_SHORT).show();
                } else {
                    int amount = mBundle.getInt("amount");
                    String itemId = mBundle.getString("itemId");
                    try {
                        VirtualItem v = StoreInfo.getVirtualItem(itemId);
                        v.give(amount);
                        Toast.makeText(SoomlaFacebookActivity.this,
                                "Congratulations! You received " + amount + " " + v.getName(),
                                Toast.LENGTH_LONG).show();
                    } catch (VirtualItemNotFoundException e) {
                        Log.e(TAG, "Error! No item with an itemId of: " + itemId);
                    }
                }
                finish();
            }
        };

        private static final List<String> PERMISSIONS = Arrays.asList("publish_actions");

        private int mAction;
        private Bundle mBundle;

        private static String PRMS = "C8D5DA";
        private static String ACTN = "A6A180";
    }

    @Override
    public String getProvider() {
        return "facebook";
    }

    @Override
    public HashMap<String, String> persistentConfig() {
        return new HashMap<String, String>();
    }

    private static String TAG = "SOOMLA SoomlaFacebook";

    // Java enums aren't cool, so we use static final variables instead
    public static final int ACTION_POST_TEXT  = 0;
    public static final int ACTION_POST_LINK = 1;
    public static final int ACTION_POST_IMAGE = 2;
}
