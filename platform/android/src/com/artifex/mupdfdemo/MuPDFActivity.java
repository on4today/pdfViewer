package com.artifex.mupdfdemo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;
import java.util.concurrent.Executor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

class ThreadPerTaskExecutor implements Executor {
	public void execute(Runnable r) {
		new Thread(r).start();
	}
}

public class MuPDFActivity extends Activity 
{
	/* The core rendering instance */
	enum AcceptMode {Highlight, Underline, StrikeOut, Ink, CopyText};


	private MuPDFCore    core;
//	private String       mFileName;
	private MuPDFReaderView mDocView;
	private AlertDialog.Builder mAlertBuilder;
	private boolean mAlertsActive= false;
	private AsyncTask<Void,Void,MuPDFAlert> mAlertTask;
	private AlertDialog mAlertDialog;
	private static final String TAG = "pdf";
	private int mZoomLevel = 1;

	public void createAlertWaiter() {
		mAlertsActive = true;
		// All mupdf library calls are performed on asynchronous tasks to avoid stalling
		// the UI. Some calls can lead to javascript-invoked requests to display an
		// alert dialog and collect a reply from the user. The task has to be blocked
		// until the user's reply is received. This method creates an asynchronous task,
		// the purpose of which is to wait of these requests and produce the dialog
		// in response, while leaving the core blocked. When the dialog receives the
		// user's response, it is sent to the core via replyToAlert, unblocking it.
		// Another alert-waiting task is then created to pick up the next alert.
		if (mAlertTask != null) {
			mAlertTask.cancel(true);
			mAlertTask = null;
		}
		if (mAlertDialog != null) {
			mAlertDialog.cancel();
			mAlertDialog = null;
		}
		mAlertTask = new AsyncTask<Void,Void,MuPDFAlert>() {

			@Override
			protected MuPDFAlert doInBackground(Void... arg0) {
				if (!mAlertsActive)
					return null;

				return core.waitForAlert();
			}

			@SuppressWarnings("deprecation")
			@Override
			protected void onPostExecute(final MuPDFAlert result) {
				// core.waitForAlert may return null when shutting down
				if (result == null)
					return;
				final MuPDFAlert.ButtonPressed pressed[] = new MuPDFAlert.ButtonPressed[3];
				for(int i = 0; i < 3; i++)
					pressed[i] = MuPDFAlert.ButtonPressed.None;
				DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						mAlertDialog = null;
						if (mAlertsActive) {
							int index = 0;
							switch (which) {
							case AlertDialog.BUTTON1: index=0; break;
							case AlertDialog.BUTTON2: index=1; break;
							case AlertDialog.BUTTON3: index=2; break;
							}
							result.buttonPressed = pressed[index];
							// Send the user's response to the core, so that it can
							// continue processing.
							core.replyToAlert(result);
							// Create another alert-waiter to pick up the next alert.
							createAlertWaiter();
						}
					}
				};
				mAlertDialog = mAlertBuilder.create();
				mAlertDialog.setTitle(result.title);
				mAlertDialog.setMessage(result.message);
				switch (result.iconType)
				{
				case Error:
					break;
				case Warning:
					break;
				case Question:
					break;
				case Status:
					break;
				}
				switch (result.buttonGroupType)
				{
				case OkCancel:
					mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.cancel), listener);
					pressed[1] = MuPDFAlert.ButtonPressed.Cancel;
				case Ok:
					mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.okay), listener);
					pressed[0] = MuPDFAlert.ButtonPressed.Ok;
					break;
				case YesNoCancel:
					mAlertDialog.setButton(AlertDialog.BUTTON3, getString(R.string.cancel), listener);
					pressed[2] = MuPDFAlert.ButtonPressed.Cancel;
				case YesNo:
					mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.yes), listener);
					pressed[0] = MuPDFAlert.ButtonPressed.Yes;
					mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.no), listener);
					pressed[1] = MuPDFAlert.ButtonPressed.No;
					break;
				}
				mAlertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
					public void onCancel(DialogInterface dialog) {
						mAlertDialog = null;
						if (mAlertsActive) {
							result.buttonPressed = MuPDFAlert.ButtonPressed.None;
							core.replyToAlert(result);
							createAlertWaiter();
						}
					}
				});

				mAlertDialog.show();
			}
		};

		mAlertTask.executeOnExecutor(new ThreadPerTaskExecutor());
	}

	public void destroyAlertWaiter() {
		mAlertsActive = false;
		if (mAlertDialog != null) {
			mAlertDialog.cancel();
			mAlertDialog = null;
		}
		if (mAlertTask != null) {
			mAlertTask.cancel(true);
			mAlertTask = null;
		}
	}

	private MuPDFCore openFile(String path)
	{
		try
		{
			core = new MuPDFCore(this, path);

		}
		catch (Exception e)
		{
			System.out.println(e);
			return null;
		}
		return core;
	}

	private MuPDFCore openBuffer(byte buffer[], String magic)
	{
		try
		{
			core = new MuPDFCore(this, buffer, magic);
			// New file: drop the old outline data

		}
		catch (Exception e)
		{
			System.out.println(e);
			return null;
		}
		return core;
	}
	
	
	/**
	 * Initializes the buttons (zoom/next page etc), and loads the PDF.
	 */
	private void startPDF() {
    	final MuPDFActivity thisPointer = this;
    	 
		// add click listeners for buttons and labels 
        final ImageView exitAppButton = (ImageView) findViewById(R.id.exitAppButton);
        final ImageView zoomInButton = (ImageView) findViewById(R.id.zoomInButton);
        final ImageView zoomOutButton = (ImageView) findViewById(R.id.zoomOutButton);
        final ImageView goUpButton = (ImageView) findViewById(R.id.goUpButton);
        final ImageView goDownButton = (ImageView) findViewById(R.id.goDownButton);
        
        
        final TextView exitAppText = (TextView) findViewById(R.id.exitAppText);
        final TextView zoomInText = (TextView) findViewById(R.id.zoomInText);
        final TextView zoomOutText = (TextView) findViewById(R.id.zoomOutText);
        final TextView goUpText= (TextView) findViewById(R.id.goUpText);
        final TextView goDownText = (TextView) findViewById(R.id.goDownText);
        final TextView pageNumberTextView = (TextView) findViewById(R.id.pageNumberText);
        
        OnClickListener buttonListener = new OnClickListener() {
			
			public void onClick(View view) {
				if (view.getId() == R.id.exitAppButton){
					System.exit(0);
				} else if (view.getId() == R.id.zoomInButton){ // zoom in
					switch (mZoomLevel){ // zoom level can go from 1-4: 1=fit height to screen, 2=fit width to screen, 3+4 = zoom in more
					case 1:
						mZoomLevel++;
						zoomOutButton.setImageResource(R.drawable.zoom_out);
						break;
					case 2:
						mZoomLevel++;
						break;
					case 3:
						mZoomLevel++;
						zoomInButton.setImageResource(R.drawable.zoom_in_disabled);
						break;
					}
					mDocView.zoomToLevel(mZoomLevel);


				} else if (view.getId() == R.id.zoomOutButton){
					
					switch (mZoomLevel){
					case 2:
						mZoomLevel--;
						zoomOutButton.setImageResource(R.drawable.zoom_out_disabled);
						break;
					case 3:
						mZoomLevel--;
						break;
					case 4:
						mZoomLevel--;
						zoomInButton.setImageResource(R.drawable.zoom_in);
						break;
					}
					mDocView.zoomToLevel(mZoomLevel);

				} else if (view.getId() == R.id.goUpButton || view.getId() == R.id.goUpText){ // go to previous page
					mDocView.moveToPrevious();	
				} else if (view.getId() == R.id.goDownButton || view.getId() == R.id.goDownText){ // go to next page
				    mDocView.moveToNext(); 
				}
				
			}
		};
        
        exitAppButton.setOnClickListener(buttonListener);
        zoomInButton.setOnClickListener(buttonListener);
        zoomOutButton.setOnClickListener(buttonListener);
        goUpButton.setOnClickListener(buttonListener);
        goDownButton.setOnClickListener(buttonListener);
        
        exitAppText.setOnClickListener(buttonListener);
        zoomInText.setOnClickListener(buttonListener);
        zoomOutText.setOnClickListener(buttonListener);
        goUpText.setOnClickListener(buttonListener);
        goDownText.setOnClickListener(buttonListener);
        
        
        
        
        
        
        
        // now load the PDF in the background
		
    	AsyncTask<Void, Void, Void> pdfLoader = new AsyncTask<Void, Void, Void>(){
    		@Override
			protected Void doInBackground(Void... params) {
    			getPDF(); // do the actual loading
    			return null;
    		}
    		    
    		@Override
    		protected void onPostExecute(Void result) {

    			// show the progress bar
    			RelativeLayout layout = (RelativeLayout) findViewById(R.id.background);
    			layout.setBackgroundResource(R.drawable.background_green);
    			View progressLayout = findViewById(R.id.progressLayout);
    			progressLayout.setVisibility(View.GONE);

    			
    			if (core == null) // if something went wrong, display error dialog and then quit
    			{
    				AlertDialog alert = mAlertBuilder.create();
    				alert.setTitle(R.string.cannot_open_document);
    				alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
    						new DialogInterface.OnClickListener() {
    							public void onClick(DialogInterface dialog, int which) {
    								finish();
    							}
    						});
    				alert.setOnCancelListener(new OnCancelListener() {

    					@Override
    					public void onCancel(DialogInterface dialog) {
    						finish();
    					}
    				});
    				alert.show();
    				return;
    			}
    			
    			

    			// Now create the UI.
    			// First create the document view
    			mDocView = new MuPDFReaderView(thisPointer) {
    				@Override
    				protected void onMoveToChild(int i) {
    					if (core == null)
    						return;

    			    	
    					pageNumberTextView.setText(String.format(getResources().getString(R.string.pageNumber), i + 1, core.countPages()));
    					if (i == 0){
    						goDownButton.setImageResource(R.drawable.next_page);
    						goUpButton.setImageResource(R.drawable.previous_page_disabled);
    					} else if (i == core.countPages() - 1) {
    						goUpButton.setImageResource(R.drawable.previous_page);
    						goDownButton.setImageResource(R.drawable.next_page_disabled);
    					} else {
    						goUpButton.setImageResource(R.drawable.previous_page);
    						goDownButton.setImageResource(R.drawable.next_page);
    					}
    					

    					super.onMoveToChild(i);
    				}

    				@Override
    				protected void onTapMainDocArea() {
    				}

    				@Override
    				protected void onDocMotion() {
    				}

    				@Override
    				protected void onHit(Hit item) {

    				}
    			};
    			mDocView.setAdapter(new MuPDFPageAdapter(thisPointer, core));
    		
    			// Reinstate last state if it was recorded
//    			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
//    			mDocView.setDisplayedViewIndex(prefs.getInt("page"+mFileName, 0));

    			// display PDF page left of menu bar
    			RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
    			lay.addRule(RelativeLayout.LEFT_OF, R.id.exitAppButton);
				
    			layout.addView(mDocView, lay);
    			pageNumberTextView.setText(String.format(getResources().getString(R.string.pageNumber), 1, core.countPages())); // show current page
    		}
    		        
    	};
    	pdfLoader.execute();
    }
	
	
	/**
	 * Loads the PDF file
	 */
	private void getPDF(){
		if (core == null) {
			final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar);
	        final TextView progressPercentText = (TextView) findViewById(R.id.progressPercentText);
			Intent intent = getIntent();
			byte buffer[] = null;
			
			String uriString = intent.getStringExtra("uri");
			Uri uri = null;
			
			if (uriString == null){ // the PDF file name should be delivered via an intent
				Log.e(TAG, "Please start with Intent to PDF file");
				uri = Uri.parse("http://www.act.org/compass/sample/pdf/reading.pdf"); 	 //this is just for development; display a PDF if started without intent
//				System.exit(0); // this should be done instead of the previous line
			} else {
				uri = Uri.parse(uriString);
			}

			
			// load PDF from tablet
			if (uri.toString().startsWith("content://")) {
				String reason = null;
				try {
					InputStream is = getContentResolver().openInputStream(uri);
					int len = is.available();
					buffer = new byte[len];
					is.read(buffer, 0, len);
					is.close();
				}
				catch (java.lang.OutOfMemoryError e) {
					Log.e(TAG, "Out of memory during buffer reading");
					reason = e.toString();
				}
				catch (Exception e) {
					Log.e(TAG, "Exception reading from stream: " + e);

					// Handle view requests from the Transformer Prime's file manager
					// Hopefully other file managers will use this same scheme, if not
					// using explicit paths.
					// I'm hoping that this case below is no longer needed...but it's
					// hard to test as the file manager seems to have changed in 4.x.
					try {
						Cursor cursor = getContentResolver().query(uri, new String[]{"_data"}, null, null, null);
						if (cursor.moveToFirst()) {
							String str = cursor.getString(0);
							if (str == null) {
								reason = "Couldn't parse data in intent";
							}
							else {
								uri = Uri.parse(str);
							}
						}
					}
					catch (Exception e2) {
						Log.e(TAG, "Exception in Transformer Prime file manager code: " + e2);
						reason = e2.toString();
					}
				}
				if (reason != null) {
					buffer = null;
					Resources res = getResources();
					AlertDialog alert = mAlertBuilder.create();
					setTitle(String.format(res.getString(R.string.cannot_open_document_Reason), reason));
					alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {
									finish();
								}
							});
					alert.show();
					return;
				}
			} else if (uri.getScheme().equals("http") || uri.getScheme().equals("https")) { // load PDF from http address
	    		try {
	    			// create Documents folder if not existing
	    			//File folder = new File(Environment.getExternalStorageDirectory().getPath() + "/Documents");

					// if the directory does not exist, create it
//					if (!folder.exists()) {
//					   folder.mkdir();
//					}
	    			
					URL url = new URL(uri.toString());
					URLConnection connection = url.openConnection();
					int size = connection.getContentLength(); // get size of document
					
					
					InputStream in = connection.getInputStream();
//						FileOutputStream fos = new FileOutputStream(new File(Environment.getExternalStorageDirectory().getPath() + "/Documents/test.pdf")); // download file
					ByteArrayOutputStream fos = new ByteArrayOutputStream();
					
					byte[] buf = new byte[4096];
					double sumCount = 0.0;
					while (true) { // actual downloading
					    int len = in.read(buf);
					    if (size > 0){
						    sumCount += len;
						    final int percentage = (int) (sumCount / size * 100);
						    runOnUiThread(new Runnable(){ // update progress bar
								public void run() {
								    progressBar.setProgress(percentage);
								    progressPercentText.setText(percentage + " %");
								}
						    });
						    

					    }
					    
					    if (len == -1) {
					        break;
					    }
					    fos.write(buf, 0, len);
					}
					buffer = fos.toByteArray();
					in.close();
					fos.flush();
					fos.close();
					
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(1);
				}
	    	}
			
			
			
			
			// load buffer
			if (buffer != null) {
				core = openBuffer(buffer, intent.getType());
			} else {
				core = openFile(Uri.decode(uri.getEncodedPath()));
			}

			if (core != null && core.needsPassword()) {
//				requestPassword(savedInstanceState); // TODO support password protected files
				return;
			}
			if (core != null && core.countPages() == 0)
			{
				core = null;
			}
		}
	}
	
	

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		mAlertBuilder = new AlertDialog.Builder(this);

//		if (core == null) {
//			core = (MuPDFCore)getLastNonConfigurationInstance();
//
//			if (savedInstanceState != null && savedInstanceState.containsKey("FileName")) {
//				mFileName = savedInstanceState.getString("FileName");
//			}
//		}
		
	
		Intent intent = getIntent();
		String language = (intent.getStringExtra("language") == null) ? "english" : intent.getStringExtra("language"); // check if language is selected via intent; otherwise, use "english"
		
		if (!language.equals("english")){ // switch to another language
			Resources res = getResources();
		    // Change locale settings in the app.
		    DisplayMetrics dm = res.getDisplayMetrics();
		    android.content.res.Configuration conf = res.getConfiguration();
		    String languageCode = "en";
		    if (language.equals("spanish"))
		    	languageCode = "es";
		    
		    conf.locale = new Locale(languageCode);
		    res.updateConfiguration(conf, dm);
		}

		// Stick the document view and the buttons overlay into a parent view
		setContentView(R.layout.mainactivity);
				
		// load the PDF
		startPDF();
	}

//	public void requestPassword(final Bundle savedInstanceState) {
//		mPasswordView = new EditText(this);
//		mPasswordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
//		mPasswordView.setTransformationMethod(new PasswordTransformationMethod());
//
//		AlertDialog alert = mAlertBuilder.create();
//		alert.setTitle(R.string.enter_password);
//		alert.setView(mPasswordView);
//		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay),
//				new DialogInterface.OnClickListener() {
//			public void onClick(DialogInterface dialog, int which) {
//				if (core.authenticatePassword(mPasswordView.getText().toString())) {
//					createUI(savedInstanceState);
//				} else {
//					requestPassword(savedInstanceState);
//				}
//			}
//		});
//		alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel),
//				new DialogInterface.OnClickListener() {
//
//			public void onClick(DialogInterface dialog, int which) {
//				finish();
//			}
//		});
//		alert.show();
//	}



//	public Object onRetainNonConfigurationInstance()
//	{
//		MuPDFCore mycore = core;
//		core = null;
//		return mycore;
//	}
//
//
//
//	@Override
//	protected void onSaveInstanceState(Bundle outState) {
//		super.onSaveInstanceState(outState);
//
//		if (mFileName != null && mDocView != null) {
//			outState.putString("FileName", mFileName);
//
//			// Store current page in the prefs against the file name,
//			// so that we can pick it up each time the file is loaded
//			// Other info is needed only for screen-orientation change,
//			// so it can go in the bundle
//			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
//			SharedPreferences.Editor edit = prefs.edit();
//			edit.putInt("page"+mFileName, mDocView.getDisplayedViewIndex());
//			edit.commit();
//		}
//
//	}

	@Override
	protected void onPause() {
		super.onPause();


//		if (mFileName != null && mDocView != null) {
//			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
//			SharedPreferences.Editor edit = prefs.edit();
//			edit.putInt("page"+mFileName, mDocView.getDisplayedViewIndex());
//			edit.commit();
//		}
	}

	public void onDestroy()
	{
		if (mDocView != null) {
			mDocView.applyToChildren(new ReaderView.ViewMapper() {
				void applyToView(View view) {
					((MuPDFView)view).releaseBitmaps();
				}
			});
		}
		if (core != null)
			core.onDestroy();
		if (mAlertTask != null) {
			mAlertTask.cancel(true);
			mAlertTask = null;
		}
		core = null;
		super.onDestroy();
	}



//	private void updatePageNumView(int index) {
//		if (core == null)
//			return;
//		
//		final ImageView goUpButton = (ImageView) findViewById(R.id.goUpButton);
//        final ImageView goDownButton = (ImageView) findViewById(R.id.goDownButton);
//        final TextView pageNumberTextView = (TextView) findViewById(R.id.pageNumberText);
//
//        
//		pageNumberTextView.setText(String.format(getResources().getString(R.string.pageNumber), index + 1, core.countPages()));
//		if (index == 0){
//			goDownButton.setImageResource(R.drawable.next_page);
//			goUpButton.setImageResource(R.drawable.previous_page_disabled);
//		} else if (index == core.countPages() - 1) {
//			goUpButton.setImageResource(R.drawable.previous_page);
//			goDownButton.setImageResource(R.drawable.next_page_disabled);
//		} else {
//			goUpButton.setImageResource(R.drawable.previous_page);
//			goDownButton.setImageResource(R.drawable.next_page);
//		}
//	}



	@Override
	protected void onStart() {
		if (core != null)
		{
			core.startAlerts();
			createAlertWaiter();
		}

		super.onStart();
	}

	@Override
	protected void onStop() {
		if (core != null)
		{
			destroyAlertWaiter();
			core.stopAlerts();
		}

		super.onStop();
	}

	
}
