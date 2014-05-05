/**
 *
 * HTMLFragment
 * Cobalt
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Cobaltians
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package fr.cobaltians.cobalt.fragments;

import fr.cobaltians.cobalt.BuildConfig;
import fr.cobaltians.cobalt.Cobalt;
import fr.cobaltians.cobalt.R;
import fr.cobaltians.cobalt.activities.CobaltActivity;
import fr.cobaltians.cobalt.customviews.IScrollListener;
import fr.cobaltians.cobalt.customviews.OverScrollingWebView;
import fr.cobaltians.cobalt.customviews.PullToRefreshOverScrollWebview;
import fr.cobaltians.cobalt.database.LocalStorageJavaScriptInterface;
import fr.cobaltians.cobalt.webViewClients.ScaleWebViewClient;

import com.handmark.pulltorefresh.library.LoadingLayoutProxy;
import com.handmark.pulltorefresh.library.PullToRefreshBase;
import com.handmark.pulltorefresh.library.PullToRefreshBase.Mode;
import com.handmark.pulltorefresh.library.PullToRefreshBase.OnRefreshListener;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.NavUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@link Fragment} allowing interactions between native and Web
 * 
 * @author Diane Moebs
 */
public abstract class HTMLFragment extends Fragment implements IScrollListener {

    // TAG
    protected final static String TAG = HTMLFragment.class.getSimpleName();
	
	/*********************************************************
	 * MEMBERS
	 ********************************************************/
	protected static Context sContext;
	
	protected String mPage;
	
	protected OverScrollingWebView mWebView;
	// TODO: use ViewGroup instead of FrameLayout to allow using different layouts 
	protected FrameLayout mWebViewPlaceholder;
	protected boolean mWebViewContentHasBeenLoaded;
	
	protected Handler mHandler;

	private ArrayList<JSONObject> mWaitingJavaScriptCallsQueue;
	
	private boolean mPreloadOnCreateView;
	private boolean mWebViewLoaded;
	private boolean mCobaltIsReady;
	
	// Web view may having pull-to-refresh and/or infinite scroll features.
	protected PullToRefreshOverScrollWebview mPullToRefreshWebView;

	private boolean mInfiniteScrollRefreshing = false;
	private boolean mInfiniteScrollEnabled = false;
	private boolean mPullToRefreshActivate = false;
	
	/**************************************************************************************************************************
	 * LIFECYCLE
	 *************************************************************************************************************************/
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setRetainInstance(true);
		
		if (sContext == null) {
			sContext = (Context) getActivity().getApplicationContext();
		}
		
		mHandler = new Handler();
		
		mWaitingJavaScriptCallsQueue = new ArrayList<JSONObject>();
		
		mPreloadOnCreateView = true;
		mWebViewLoaded = false;
		mWebViewContentHasBeenLoaded = false;
		mCobaltIsReady = false;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		super.onCreateView(inflater, container, savedInstanceState);
		
		View view = inflater.inflate(getLayoutToInflate(), container, false);
		setUpViews(view);
		setUpListeners();
		return view;
	}	

	/**
	 * Restores Web view state.
	 */
	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		
		if (mWebView != null) {
			mWebView.restoreState(savedInstanceState);
		}
	}
	
	@Override
	public void onStart() {
		super.onStart();

		addWebView();
		preloadContent();
		
		setFeaturesWantedActive();
		
		// Web view has been added, set up its listener
		if (mPullToRefreshWebView != null) {		
			mPullToRefreshWebView.setOnRefreshListener(new OnRefreshListener<OverScrollingWebView>() {
				@Override
				public void onRefresh(PullToRefreshBase<OverScrollingWebView> refreshView) {
					refreshWebView();
				}
			});
		}
	}

	/**
	 * Saves the Web view state.
	 */
	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		
		if(mWebView != null) {
			mWebView.saveState(outState);
		}
	}
	
	@Override
	public void onStop() {
		super.onStop();
		
		// Fragment will rotate or be destroyed, so we don't preload content defined in fragment's arguments again 
		mPreloadOnCreateView = false;
		
		removeWebViewFromPlaceholder();
	}
	
	/****************************************************************************************
	 * Helpers
	 ***************************************************************************************/
	
	/**
	 * This method should be overridden in subclasses.
	 * @return Layout id inflated by this fragment
	 */
	protected int getLayoutToInflate() {
		return R.layout.fragment_cobalt;
	}

	/**
	 * Sets up the fragment's properties according to the inflated layout.
	 * This method should be overridden in subclasses. 
	 * @param rootView: parent view
	 */
	protected void setUpViews(View rootView) {
		// TODO: and if webViewPlaceholder id is null?
		mWebViewPlaceholder = ((FrameLayout) rootView.findViewById(R.id.webViewPlaceholder));
	}

	/**
	 * Sets up listeners for components inflated from the given layout and the parent view.
	 * This method should be overridden in subclasses.
	 */
	protected void setUpListeners() { }
	
	/**
	 * Called to add the Web view in the placeholder (and creates it if necessary).
	 * This method SHOULD NOT be overridden in subclasses.
	 */
	protected void addWebView() {
				
		if(mWebView == null) {
			mWebView = new OverScrollingWebView(sContext);
			setWebViewSettings(this);
		}	
		
		mPullToRefreshActivate = pullToRefreshActivate();
		if (mPullToRefreshActivate) {
			if (mPullToRefreshWebView == null) {
				mPullToRefreshWebView = new PullToRefreshOverScrollWebview(sContext);
				mWebView = mPullToRefreshWebView.getRefreshableView();
				setWebViewSettings(this);
			}
		}
		
		if(mWebViewPlaceholder != null) {
			if (mPullToRefreshActivate) {
				mWebViewPlaceholder.addView(mPullToRefreshWebView);
			}
			else mWebViewPlaceholder.addView(mWebView);
		}	
		else  {
			if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - addWebView: you must set up Web view placeholder in setUpViews!");
		}		
	}
	
	private void preloadContent() {
		// TODO: mPage setting. Do we keep this behavior?
		Bundle arguments = getArguments();
		
		if (arguments != null) {
			mPage = arguments.getString(Cobalt.kPage);
		}
		mPage = (mPage != null) ? mPage : "index.html";
		
		if (mPreloadOnCreateView) {
			loadFileFromAssets(mPage);
		}
	}
	
	/**
	 * Called when fragment is about to rotate or be destroyed
	 * This method SHOULD NOT be overridden in subclasses.
	 */
	protected void removeWebViewFromPlaceholder() {
		if (mWebViewPlaceholder != null) {
			if (mPullToRefreshWebView != null) {
				mWebViewPlaceholder.removeView(mPullToRefreshWebView);
			}
			if (mWebView != null) {
				mWebViewPlaceholder.removeView(mWebView);
			}
		}
		else if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - removeWebViewFromPlaceholder: you must set up Web view placeholder in setUpViews!");
	}
	
	@SuppressLint("SetJavaScriptEnabled")
	protected void setWebViewSettings(Object javascriptInterface) {
		if(mWebView != null) {		
			mWebView.setScrollListener(this);
			mWebView.setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
			
			// Enables JS
			WebSettings webSettings = mWebView.getSettings();
			webSettings.setJavaScriptEnabled(true);

			// Enables and setup JS local storage
			webSettings.setDomStorageEnabled(true); 
			webSettings.setDatabaseEnabled(true);
			//@deprecated since API 19. But calling this method have simply no effect for API 19+
			webSettings.setDatabasePath(sContext.getFilesDir().getParentFile().getPath()+"/databases/");
			
			// Enables cross-domain calls for Ajax
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
				allowAjax();
			}
			
			// Fix some focus issues on old devices like HTC Wildfire
			// keyboard was not properly showed on input touch.
			mWebView.requestFocus(View.FOCUS_DOWN);
			mWebView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View view, MotionEvent event) {
					switch (event.getAction()) {
						case MotionEvent.ACTION_DOWN:
						case MotionEvent.ACTION_UP:
							if (! view.hasFocus()) {
								view.requestFocus();
							}
							break;
						default:
							break;
					}
					
					return false;
				}
			});

			// Add JavaScript interface so JavaScript can call native functions.
			mWebView.addJavascriptInterface(javascriptInterface, "Android");
			mWebView.addJavascriptInterface(new LocalStorageJavaScriptInterface(sContext), "LocalStorage");

			ScaleWebViewClient scaleWebViewClient = new ScaleWebViewClient() {
				@Override
				public void onPageStarted(WebView view, String url, Bitmap favicon) {
					super.onPageStarted(view, url, favicon);
					
					mWebViewLoaded = false;
				}

				@Override
				public void onPageFinished(WebView view, String url) {
					mWebViewLoaded = true;
					
					executeWaitingCalls();
				}
			};
			scaleWebViewClient.setScaleListener(this);

			mWebView.setWebViewClient(scaleWebViewClient);
		}
		else {
			if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - setWebViewSettings: Web view is null.");
		}
	}
	
	@TargetApi(android.os.Build.VERSION_CODES.JELLY_BEAN) // 16
	private void allowAjax() {
		try {
			// TODO: see how to restrict only to local files
			mWebView.getSettings().setAllowUniversalAccessFromFileURLs(true);
		} 
		catch(NullPointerException exception) {
			exception.printStackTrace();
		}
	}
	
	/**
	 * Load the given file in the Web view
	 * @param file: file name to load.
	 * @warning All application HTML files should be found in the same subfolder in ressource path
	 */
	private void loadFileFromAssets(String file) {
		if(mWebView != null) {
			mWebView.loadUrl(Cobalt.getInstance(sContext).getResourcePath() + file);
			mWebViewContentHasBeenLoaded = true;
		}
	}
	
	/****************************************************************************************
	 * SCRIPT EXECUTION
	 ***************************************************************************************/
	// TODO: find a way to keep in the queue not sent messages
	/**
	 * Sends script to be executed by JavaScript in Web view
	 * @param jsonObj: JSONObject containing script.
	 */
	public void executeScriptInWebView(final JSONObject jsonObj) {
		if (jsonObj != null) {
			if(mCobaltIsReady) {
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						// Line & paragraph separators are not JSON compliant but supported by JSONObject
						String message = jsonObj.toString().replaceAll("[\u2028\u2029]", "");
						String url = "javascript:cobalt.execute(" + message + ");";
						
						if(mWebView != null) {
							if (BuildConfig.DEBUG) Log.i(Cobalt.TAG, TAG + " - executeScriptInWebView: " + message);
							mWebView.loadUrl(url);		
						}
						else {
							if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - executeScriptInWebView: message cannot been sent to empty Web view");
						}						
					}
				});
			}
			else {
				if (BuildConfig.DEBUG) Log.i(Cobalt.TAG, TAG + " - executeScriptInWebView: adding message to queue " + jsonObj);
				mWaitingJavaScriptCallsQueue.add(jsonObj);
			}
		}
	}

	private void executeWaitingCalls() {
		int mWaitingJavaScriptCallsQueueLength = mWaitingJavaScriptCallsQueue.size();
		
		for (int i = 0 ; i < mWaitingJavaScriptCallsQueueLength ; i++) {
			if (BuildConfig.DEBUG) Log.i(Cobalt.TAG, TAG + " - executeWaitingCalls: execute {" + mWaitingJavaScriptCallsQueue.get(i).toString() + "}");
			executeScriptInWebView(mWaitingJavaScriptCallsQueue.get(i));
		}
		
		mWaitingJavaScriptCallsQueue.clear();
	}

	/****************************************************************************************
	 * MESSAGE SENDING
	 ***************************************************************************************/
	/**
	 * Calls the Web callback with an object containing response fields
	 * @param callbackId: the Web callback.
	 * @param data: the object containing response fields
	 */
	public void sendCallback(final String callbackId, final JSONObject data) {
		if(	callbackId != null 
			&& callbackId.length() > 0) {
			try {
				JSONObject jsonObj = new JSONObject();
				jsonObj.put(Cobalt.kJSType, Cobalt.JSTypeCallBack);
				jsonObj.put(Cobalt.kJSCallback, callbackId);
				jsonObj.put(Cobalt.kJSData, data);
				executeScriptInWebView(jsonObj);
			} 
			catch (JSONException exception) {
				exception.printStackTrace();
			}
		}
	}
	
	public void sendEvent(final String event, final JSONObject data, final String callbackID) {
		if (event != null
			&& event.length() > 0) {
			try {
				JSONObject jsonObj = new JSONObject();
				jsonObj.put(Cobalt.kJSType, Cobalt.JSTypeEvent);
				jsonObj.put(Cobalt.kJSEvent, event);
				jsonObj.put(Cobalt.kJSData, data);
				jsonObj.put(Cobalt.kJSCallback, callbackID);
				executeScriptInWebView(jsonObj);
			}
			catch (JSONException exception) {
				exception.printStackTrace();
			}
		}
	}
	
	/****************************************************************************************
	 * MESSAGE HANDLING
	 ***************************************************************************************/
	/**
	 * This method is called when the JavaScript sends a message to the native side.
	 * This method should be overridden in subclasses.
	 * @param message : the JSON-message sent by JavaScript.
	 * @return true if the message was handled by the native, false otherwise
	 * @details some basic operations are already implemented : navigation, logs, toasts, native alerts, web alerts
	 * @details this method may be called from a secondary thread.
	 */
	// This method must be public !!!
	@JavascriptInterface
	public boolean handleMessageSentByJavaScript(String message) {
		try {
			final JSONObject jsonObj = new JSONObject(message);
			
			// TYPE
			if (jsonObj.has(Cobalt.kJSType)) {
				String type = jsonObj.getString(Cobalt.kJSType);
				
				//CALLBACK
				if (type.equals(Cobalt.JSTypeCallBack)) {
					String callbackID = jsonObj.getString(Cobalt.kJSCallback);
					JSONObject data = jsonObj.optJSONObject(Cobalt.kJSData);
					
					return handleCallback(callbackID, data);		
				}
				
				// COBALT IS READY
				else if (type.equals(Cobalt.JSTypeCobaltIsReady)) {
					onReady();
					return true;
				}
				
				// EVENT
				if (type.equals(Cobalt.JSTypeEvent)) {
					String event = jsonObj.getString(Cobalt.kJSEvent);
					JSONObject data = jsonObj.optJSONObject(Cobalt.kJSData);
					String callback = jsonObj.optString(Cobalt.kJSCallback, null);
					
					return handleEvent(event, data, callback);			
				}
				
				// LOG
				else if (type.equals(Cobalt.JSTypeLog)) {
					String text = jsonObj.getString(Cobalt.kJSValue);
					if (BuildConfig.DEBUG) Log.d(Cobalt.TAG, TAG + " - handleMessageSentByJavaScript: JS LOG \"" + text + "\"");
					return true;
				}
				
				// NAVIGATION
				else if (type.equals(Cobalt.JSTypeNavigation)) {
					String action = jsonObj.getString(Cobalt.kJSAction);
					
					// PUSH
					if (action.equals(Cobalt.JSActionNavigationPush)) {
						JSONObject data = jsonObj.getJSONObject(Cobalt.kJSData);
						String page = data.getString(Cobalt.kJSPage);
						String controller = data.optString(Cobalt.kJSController, null);
						push(controller, page);
						return true;
					}
					
					// POP
					else if (action.equals(Cobalt.JSActionNavigationPop)) {
						pop();
						return true;
					}
					
					// MODAL
					else if (action.equals(Cobalt.JSActionNavigationModal)) {
						JSONObject data = jsonObj.getJSONObject(Cobalt.kJSData);
						String page = data.getString(Cobalt.kJSPage);
						String controller = data.optString(Cobalt.kJSController, null);
						String callBackId = jsonObj.optString(Cobalt.kJSCallback, null);
						presentModal(controller, page, callBackId);
						return true;
					}
					
					// DISMISS
					else if (action.equals(Cobalt.JSActionNavigationDismiss)) {
						// TODO: not present in iOS
						JSONObject data = jsonObj.getJSONObject(Cobalt.kJSData);
						String controller = data.getString(Cobalt.kJSController);
						String page = data.getString(Cobalt.kJSPage);
						dismissModal(controller, page);
						return true;
					}
					
					// UNHANDLED NAVIGATION
					else {
						onUnhandledMessage(jsonObj);
					}
				}
				
				// UI
		    	else if (type.equals(Cobalt.JSTypeUI)) {
			    	String control = jsonObj.getString(Cobalt.kJSUIControl);
					JSONObject data = jsonObj.getJSONObject(Cobalt.kJSData);
					String callback = jsonObj.optString(Cobalt.kJSCallback, null);

					return handleUi(control, data, callback);
		    	}
				
				// WEB LAYER
				else if (type.equals(Cobalt.JSTypeWebLayer)) {
					String action = jsonObj.getString(Cobalt.kJSAction);
					
					// SHOW
					if (action.equals(Cobalt.JSActionWebLayerShow)) {
						final JSONObject data = jsonObj.getJSONObject(Cobalt.kJSData);
						
						mHandler.post(new Runnable() {
							@Override
							public void run() {
								showWebLayer(data);
							}
						});
						
						return true;
					}
					
					// UNHANDLED ACTION
					else {
						onUnhandledMessage(jsonObj);
					}
				}

                // INTENT
                else if (type.equals(Cobalt.JSTypeIntent)) {
                    String action = jsonObj.getString(Cobalt.kJSAction);

                    // OPEN EXTERNAL URL
                    if (action.equals(Cobalt.JSActionOpenExternalUrl)) {
                        JSONObject data = jsonObj.getJSONObject(Cobalt.kJSData);
                        String url = data.optString(Cobalt.kJSUrl, null);
                        openExternalUrl(url);

                        return true;
                    }
                }
				// UNHANDLED TYPE
				else {
					onUnhandledMessage(jsonObj);
				}
			}
			
			// UNHANDLED MESSAGE
			else {
				onUnhandledMessage(jsonObj);
			}
		} 
		catch (JSONException exception) {
			exception.printStackTrace();
		}
		
		return false;
	}
	
	protected void onReady() {
		if (BuildConfig.DEBUG) Log.i(Cobalt.TAG, TAG + " - onReady");

		mCobaltIsReady = true;
		executeWaitingCalls();
	}
	
	private boolean handleCallback(String callback, JSONObject data) {
		try {
			if(callback.equals(Cobalt.JSCallbackOnBackButtonPressed)) {
				onBackPressed(data.getBoolean(Cobalt.kJSValue));
				return true;
			}
			else if (callback.equals(Cobalt.JSCallbackPullToRefreshDidRefresh)) {
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						onPullToRefreshDidRefresh();
					}
				});
				return true;
			}
			else if (callback.equals(Cobalt.JSCallbackInfiniteScrollDidRefresh)) {
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						onInfiniteScrollDidRefresh();
					}
				});
				return true;
			}
			else {
				return onUnhandledCallback(callback, data);
			}
		} 
		catch (JSONException exception) {
			exception.printStackTrace();
		}
		
		return false;
	}
	
	protected abstract boolean onUnhandledCallback(String callback, JSONObject data);
	
	private boolean handleEvent(String event, JSONObject data, String callback) {
		return onUnhandledEvent(event, data, callback);
	}
	
	protected abstract boolean onUnhandledEvent(String event, JSONObject data, String callback);
	
	private boolean handleUi(String control, JSONObject data, String callback) {
		try {
			// PICKER
			if (control.equals(Cobalt.JSControlPicker)) {
				String type = data.getString(Cobalt.kJSType);
				
				//DATE
				if (type.equals(Cobalt.JSPickerDate)) {
					JSONObject date = data.optJSONObject(Cobalt.kJSDate);

					Calendar calendar = Calendar.getInstance();
					int year = calendar.get(Calendar.YEAR);
					int month = calendar.get(Calendar.MONTH);
					int day = calendar.get(Calendar.DAY_OF_MONTH);

					if (date != null
						&& date.has(Cobalt.kJSYear)
						&& date.has(Cobalt.kJSMonth)
						&& date.has(Cobalt.kJSDay)) {
						year = date.getInt(Cobalt.kJSYear);
						month = date.getInt(Cobalt.kJSMonth) - 1;
						day = date.getInt(Cobalt.kJSDay);
					}
					
					JSONObject texts = data.optJSONObject(Cobalt.kJSTexts);
					String title = texts.optString(Cobalt.kJSTitle, null);
					String delete = texts.optString(Cobalt.kJSDelete, null);
					String cancel = texts.optString(Cobalt.kJSCancel, null);
					String validate = texts.optString(Cobalt.kJSValidate, null);
					
					showDatePickerDialog(year, month, day, title, delete, cancel, validate, callback);
					
					return true;
				}
			}
			
			// ALERT
			else if(control.equals(Cobalt.JSControlAlert)) {
				showAlertDialog(data, callback);
				return true;
			}
			
			// TOAST
			else if(control.equals(Cobalt.JSControlToast)) {
				String message = data.getString(Cobalt.kJSMessage);
				Toast.makeText(sContext, message, Toast.LENGTH_SHORT).show();
				return true;
			}
		} 
		catch (JSONException exception) {
			exception.printStackTrace();
		}
		
		// UNHANDLED UI
		try {
			JSONObject jsonObj = new JSONObject();
			jsonObj.put(Cobalt.kJSType, Cobalt.JSTypeUI);
			jsonObj.put(Cobalt.kJSUIControl, control);
			jsonObj.put(Cobalt.kJSData, data);
			jsonObj.put(Cobalt.kJSCallback, callback);
			onUnhandledMessage(jsonObj);
		}
		catch (JSONException exception) {
			exception.printStackTrace();
		}
		
		return false;
	}
	
	protected abstract void onUnhandledMessage(JSONObject message);
	
	/*****************************************************************************************************************
	 * NAVIGATION
	 ****************************************************************************************************************/
	private void push(String controller, String page) {
		Intent intent = Cobalt.getInstance(sContext).getIntentForController(controller, page);
		if(intent != null) {
			getActivity().startActivity(intent);
		}
		else if (BuildConfig.DEBUG) Log.w(Cobalt.TAG,  TAG + " - push: Unable to push " + controller + " controller");
	}
	
	private void pop() {
		onBackPressed(true);
	}
	
	private void presentModal(String controller, String page, String callBackID) {
		Intent intent = Cobalt.getInstance(sContext).getIntentForController(controller, page);
		
		if (intent != null) {
			getActivity().startActivity(intent);
			// Sends callback to store current activity & HTML page for dismiss
			try {
				JSONObject data = new JSONObject();
				data.put(Cobalt.kJSPage, mPage);
				data.put(Cobalt.kJSController, getActivity() != null ? getActivity().getClass().getName() : null);
				sendCallback(callBackID, data);
			} 
			catch (JSONException exception) {
				exception.printStackTrace();
			}
		}
		else if (BuildConfig.DEBUG) Log.e(Cobalt.TAG,  TAG + " - presentModal: Unable to present modal " + controller + " controller");
	}

	private void dismissModal(String controller, String page) {
		try {
			Class<?> pClass = Class.forName(controller);

			// Instantiates intent only if class inherits from Activity
			if (Activity.class.isAssignableFrom(pClass)) {
				Bundle bundle = new Bundle();
				bundle.putString(Cobalt.kPage, page);

				Intent intent = new Intent(sContext, pClass);
				intent.putExtra(Cobalt.kExtras, bundle);

				NavUtils.navigateUpTo(getActivity(), intent);
			}
			else if(BuildConfig.DEBUG) Log.e(Cobalt.TAG,  TAG + " - dismissModal: unable to dismiss modal since " + controller + " does not inherit from Activity");
		} 
		catch (ClassNotFoundException exception) {
			if (BuildConfig.DEBUG) Log.e(Cobalt.TAG,  TAG + " - dismissModal: " + controller + "not found");
			exception.printStackTrace();
		}
	}
	
	/**
	 * Called when onBackPressed event is fired. Asks the Web view for back permission.
	 * This method should NOT be overridden in subclasses.
	 */
	public void askWebViewForBackPermission() {
		sendEvent(Cobalt.JSEventOnBackButtonPressed, null, Cobalt.JSCallbackOnBackButtonPressed);
	}
	
	/**
	 * Called when the Web view allowed or not the onBackPressed event.
	 * @param allowedToBack: 	if true, the onBackPressed method of activity will be called, 
	 * 							onBackDenied() will be called otherwise
	 * @details This method should not be overridden in subclasses
	 */
	protected void onBackPressed(boolean allowedToBack) {
		if (allowedToBack) {
			CobaltActivity activity = (CobaltActivity) getActivity();
			if (activity != null) {
				activity.back();
			}
			else if(BuildConfig.DEBUG) Log.e(Cobalt.TAG,  TAG + " - onBackPressed: activity is null, cannot call back");
		}
		else {
			onBackDenied();
		}
	}
	
	/**
	 * Called when onBackPressed event is denied by the Web view.
	 * @details This method may be overridden in subclasses.
	 */
	protected void onBackDenied() {
		if(BuildConfig.DEBUG) Log.i(Cobalt.TAG, TAG + " - onBackDenied: onBackPressed event denied by Web view");
	}
	
	/***********************************************************************************************************************************
	 * WEB LAYER
	 **********************************************************************************************************************************/
	private void showWebLayer(JSONObject data) {
		if (getActivity() != null) {
			try {
				String page = data.getString(Cobalt.kJSPage);
				double fadeDuration = data.optDouble(Cobalt.kJSWebLayerFadeDuration, 0.3);

				Bundle bundle = new Bundle();
				bundle.putString(Cobalt.kPage, page);
				
				HTMLWebLayerFragment webLayerFragment = getWebLayerFragment();
				webLayerFragment.setArguments(bundle);

				android.support.v4.app.FragmentTransaction fragmentTransition;
				fragmentTransition = getActivity().getSupportFragmentManager().beginTransaction();

				if (fadeDuration > 0) {
					fragmentTransition.setCustomAnimations(	android.R.anim.fade_in, android.R.anim.fade_out, 
															android.R.anim.fade_in, android.R.anim.fade_out);
				}
				else {
					fragmentTransition.setTransition(FragmentTransaction.TRANSIT_NONE);
				}

				if (CobaltActivity.class.isAssignableFrom(getActivity().getClass())) {
					// Dismiss current Web layer if one is already shown
					CobaltActivity activity = (CobaltActivity) getActivity();
					Fragment currentFragment = activity.getSupportFragmentManager().findFragmentById(activity.getFragmentContainerId());
					if (HTMLWebLayerFragment.class.isAssignableFrom(currentFragment.getClass())) {
						HTMLWebLayerFragment webLayerToDismiss = (HTMLWebLayerFragment) currentFragment;
						webLayerToDismiss.dismissWebLayer(null);
					}

					// Shows Web layer
					if (activity.findViewById(activity.getFragmentContainerId()) != null) {
						fragmentTransition.add(activity.getFragmentContainerId(), webLayerFragment);
						fragmentTransition.commit();
					}
					else if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - showWebLayer: fragment container not found");
				}
			} 
			catch (JSONException exception) {
				exception.printStackTrace();
			}
		}
		else if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - showWebLayer: unable to show a Web layer from a fragment not attached to an activity!");
	}
	
	/**
	 * Returns new instance of a {@link HTMLWebLayerFragment}
	 * @return a new instance of a {@link HTMLWebLayerFragment}
	 * This method may be overridden in subclasses if the {@link HTMLWebLayerFragment} must implement customized stuff.
	 */
	protected HTMLWebLayerFragment getWebLayerFragment() {
		return new HTMLWebLayerFragment();
	}
	
	/**
	 * Called from the corresponding {@link HTMLWebLayerFragment} when dismissed.
	 * This method may be overridden in subclasses.
	 */
	public void onWebLayerDismiss(final String page, final JSONObject data) {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					JSONObject jsonObj = new JSONObject();
					jsonObj.put(Cobalt.kJSPage, page);
					jsonObj.put(Cobalt.kJSData, data);

					sendEvent(Cobalt.JSEventWebLayerOnDismiss, jsonObj, null);
				} 
				catch (JSONException exception) {
					exception.printStackTrace();
				}
			}
		});
	}
	
	/******************************************************************************************************************
	 * ALERT DIALOG
	 *****************************************************************************************************************/
	private void showAlertDialog(JSONObject data, final String callback) {		
		try {
			String title = data.optString(Cobalt.kJSAlertTitle);
			String message = data.optString(Cobalt.kJSMessage);
			boolean cancelable = data.optBoolean(Cobalt.kJSAlertCancelable, false);
			JSONArray buttons = data.has(Cobalt.kJSAlertButtons) ? data.getJSONArray(Cobalt.kJSAlertButtons) : new JSONArray();

			AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
			alert.setTitle(title);
			alert.setMessage(message);

			AlertDialog mAlert = alert.create();
			mAlert.setCancelable(cancelable);
			
			if (buttons.length() == 0) {
				mAlert.setButton(DialogInterface.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {	
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if (callback != null) {
							try {
								JSONObject data = new JSONObject();
								data.put(Cobalt.kJSAlertButtonIndex, 0);
								sendCallback(callback, data);
							} 
							catch (JSONException exception) {
								exception.printStackTrace();
							}								
						}
					}
				});
			}
			else {
				int realSize = Math.min(buttons.length(), 3);
				for (int i = 1 ; i <= realSize ; i++) {
					mAlert.setButton(-i, buttons.getString(i-1), new DialogInterface.OnClickListener() {	
						@Override
						public void onClick(DialogInterface dialog, int which) {
							if (callback != null) {
								try {
									JSONObject data = new JSONObject();
									data.put(Cobalt.kJSAlertButtonIndex, -which-1);
									sendCallback(callback, data);
								} 
								catch (JSONException exception) {
									exception.printStackTrace();
								}
							}
						}
					});
				}
			}
			
			mAlert.show();
		} 
		catch (JSONException exception) {
			exception.printStackTrace();
		}
	}
	
	/*************************************************************************************
     * DATE PICKER
     ************************************************************************************/
    protected void showDatePickerDialog(int year, int month, int day, String title, String delete, String cancel, String validate, String callbackID) {
    	Bundle args = new Bundle();
    	args.putInt(HTMLDatePickerFragment.ARG_YEAR, year);
    	args.putInt(HTMLDatePickerFragment.ARG_MONTH, month);
    	args.putInt(HTMLDatePickerFragment.ARG_DAY, day);
    	args.putString(HTMLDatePickerFragment.ARG_TITLE, title);
    	args.putString(HTMLDatePickerFragment.ARG_DELETE, delete);
    	args.putString(HTMLDatePickerFragment.ARG_CANCEL, cancel);
    	args.putString(HTMLDatePickerFragment.ARG_VALIDATE, validate);
    	args.putString(HTMLDatePickerFragment.ARG_CALLBACK_ID, callbackID);
    	
    	HTMLDatePickerFragment newFragment = new HTMLDatePickerFragment();
        newFragment.setArguments(args);
        newFragment.setListener(this);
        
        newFragment.show(getActivity().getSupportFragmentManager(), "datePicker");
    }
    
    protected void sendDate(int year, int month, int day, String callbackID) {
    	try {
    		JSONObject jsonDate = new JSONObject();
    		if (year != 0 || month != 0 || day != 0) {	
    			jsonDate.put(Cobalt.kJSYear, year);
    			month++;
    			jsonDate.put(Cobalt.kJSMonth, month);
    			jsonDate.put(Cobalt.kJSDay, day);
    		}
    		JSONObject jsonResponse = new JSONObject();
    		jsonResponse.put(Cobalt.kJSType, Cobalt.JSTypeCallBack);
			jsonResponse.put(Cobalt.kJSCallback, callbackID);
			jsonResponse.put(Cobalt.kJSData, jsonDate);
			executeScriptInWebView(jsonResponse);
		} catch (JSONException e) {
			e.printStackTrace();
		}
    }

    /********************************************************
     * External Url
     ********************************************************/
    private void openExternalUrl(String url) {
        if (url != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        }

    }

    /******************************************************************************************************************************
	 * PULL TO REFRESH
	 *****************************************************************************************************************************/
	
	/**
	 * Enables pull-to-refresh feature
	 * mPullRefreshWebView must be set
	 */
	
	public void enablePullToRefresh() {
		if (mPullToRefreshWebView != null) {
			mPullToRefreshWebView.setMode(Mode.PULL_FROM_START);
		}
		else if(BuildConfig.DEBUG) Log.e(Cobalt.TAG, TAG + " - enablePullToRefresh: unable to enable pull-to-refresh feature. mPullToRefreshWebView must be set.");
	}
	
	/**
	 * Disables pull-to-refresh feature
	 * mPullRefreshWebView must be set 
	 */
	public void disablePullToRefresh() {
		if(mPullToRefreshWebView != null) {
			mPullToRefreshWebView.setMode(Mode.DISABLED);
			mPullToRefreshActivate = false;
		}
		else if(BuildConfig.DEBUG && mPullToRefreshActivate) Log.e(Cobalt.TAG, TAG + " - disablePullToRefresh: Unable to disable pull-to-refresh feature. mPullToRefreshWebView must be set.");
	}
	
	/**
	 * Returns a boolean to know if pull-to-refresh feature is enabled
	 * @return 	true if pull-to-refresh is enabled, 
	 * 			false otherwise
	 */
	public boolean isPullToRefreshEnabled() {
		return ! (mPullToRefreshWebView.getMode() == Mode.DISABLED);
	}
	
	/**
	 * Customizes pull-to-refresh loading view.
	 * @param pullLabel: text shown when user pulling
	 * @param refreshingLabel: text shown while refreshing
	 * @param releaseLabel: text shown when refreshed
	 * @param lastUpdatedLabel: text shown for the last update
	 * @param loadingDrawable: drawable shown when user pulling
	 * @details loadingDrawable animation or labels text color customization must be done in the layout.
	 * @example ptr:ptrAnimationStyle="flip|rotate"
	 */
	protected void setCustomTitlesAndImage(	String pullLabel, String refreshingLabel, String releaseLabel, String lastUpdatedLabel, 
											Drawable loadingDrawable, Typeface typeface) {
		LoadingLayoutProxy loadingLayoutProxy = ((LoadingLayoutProxy) mPullToRefreshWebView.getLoadingLayoutProxy());
		if (lastUpdatedLabel != null) {
			loadingLayoutProxy.setLastUpdatedLabel(lastUpdatedLabel);
		}
		if (pullLabel != null) {
			loadingLayoutProxy.setPullLabel(pullLabel);
		}
		if (refreshingLabel != null) {
			loadingLayoutProxy.setRefreshingLabel(refreshingLabel);
		}
		if (releaseLabel != null) {
			loadingLayoutProxy.setReleaseLabel(releaseLabel);
		}
		if (loadingDrawable != null) {
			loadingLayoutProxy.setLoadingDrawable(loadingDrawable);
		}
		if (typeface != null) {
			loadingLayoutProxy.setTextTypeface(typeface);
		}
	}
	
	/**
	 * Customizes pull-to-refresh last updated label
	 * @param text: text of last updated label
	 */
	protected void setLastUpdatedLabel(String text) {
		LoadingLayoutProxy loadingLayoutProxy = (LoadingLayoutProxy) mPullToRefreshWebView.getLoadingLayoutProxy();
		if (text != null) {
			loadingLayoutProxy.setLastUpdatedLabel(text);
		}
	}
	
	private void refreshWebView() {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					JSONObject jsonObj = new JSONObject();
					jsonObj.put(Cobalt.kJSType, Cobalt.JSTypeEvent);
					jsonObj.put(Cobalt.kJSEvent, Cobalt.JSEventPullToRefresh);
					jsonObj.put(Cobalt.kJSCallback, Cobalt.JSCallbackPullToRefreshDidRefresh);
					executeScriptInWebView(jsonObj);
				} 
				catch (JSONException exception) {
					exception.printStackTrace();
				}
			}
		});
	}
	
	private void onPullToRefreshDidRefresh() {
		mPullToRefreshWebView.onRefreshComplete();
		onPullToRefreshRefreshed();
	}

	/**
	 * This method may be overridden in subclasses.
	 */
	protected abstract void onPullToRefreshRefreshed();

	
	/************************************************************************************
	 * INFINITE SCROLL
	 ***********************************************************************************/
	
	@Override
	public void onOverScrolled(int scrollX, int scrollY,int oldscrollX, int oldscrollY) {
		float density = sContext.getResources().getDisplayMetrics().density;
		// Round density in case it is too precise (and big)
		if (density > 1) {
			density = (float) (Math.floor(density * 10) / 10.0);
		}
		
		int yPosition = (int) ((mWebView.getScrollY() + mWebView.getHeight()) / density);
		if (yPosition >= mWebView.getContentHeight()) {
			infiniteScrollRefresh();
		}
	}
	
	/**
	 * Enables infinite scroll feature
	 */
	public void enableInfiniteScroll() {
		mInfiniteScrollEnabled = true;
	}

	/**
	 * Disables infinite scroll feature
	 */
	public void disableInfiniteScroll() {
		mInfiniteScrollEnabled = false;
	}

	/**
	 * Returns a boolean to know if the infinite scroll feature is enabled
	 * @return 	true if infinite scroll is enabled, 
	 * 			false otherwise
	 */
	public boolean isInfiniteScrollEnabled() {
		return mInfiniteScrollEnabled;
	}

	private void infiniteScrollRefresh() {
		if (mInfiniteScrollEnabled 
			&& ! mInfiniteScrollRefreshing) {
			mHandler.post(new Runnable() {
				@Override
				public void run() {
					try {
						JSONObject jsonObj = new JSONObject();
						jsonObj.put(Cobalt.kJSType, Cobalt.JSTypeEvent);
						jsonObj.put(Cobalt.kJSEvent, Cobalt.JSEventInfiniteScroll);
						jsonObj.put(Cobalt.kJSCallback, Cobalt.JSCallbackInfiniteScrollDidRefresh);
						executeScriptInWebView(jsonObj);
						mInfiniteScrollRefreshing = true;
					} 
					catch (JSONException exception) {
						exception.printStackTrace();
					}
				}
			});
		}
	}
	
	private void onInfiniteScrollDidRefresh() {
		mInfiniteScrollRefreshing = false;
		onInfiniteScrollRefreshed();
	}

	/**
	 * This method may be overridden in subclasses.
	 */
	protected abstract void onInfiniteScrollRefreshed();
	
	
    /*****************************************************************
	 * HELPERS
	 ****************************************************************/
	
	private void setFeaturesWantedActive() {
		Boolean argsPTR = pullToRefreshActivate();	
		if(argsPTR) {
			enablePullToRefresh();
		}
		else {
			disablePullToRefresh();
		}
		
		mInfiniteScrollEnabled = infiniteScrollEnabled();
	}
	
	private boolean pullToRefreshActivate() {
		Bundle args = getArguments();
		if (args != null)	{
			return args.getBoolean(Cobalt.kPullToRefresh);
		}
		else {
			disablePullToRefresh();
			return false;
		}
	}
	
	private boolean infiniteScrollEnabled() {
		Bundle args = getArguments();
		if (args != null) {
			return args.getBoolean(Cobalt.kInfiniteScroll);
		}
		else {
			mInfiniteScrollEnabled = false;
			return false;
		}
	}
	/**
	 * Fired by {@link ScaleWebViewClient} to inform Web view its scale changed (pull-to-refresh need to know that to show its header appropriately).
	 */
	public void notifyScaleChange(float oldScale, float newScale) {
		mPullToRefreshWebView.setWebviewScale(newScale);
	}
}
