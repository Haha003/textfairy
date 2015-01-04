/*
 * Copyright (C) 2012,2013 Renard Wellnitz.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.tesseract.android;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.googlecode.leptonica.android.Pix;
import com.googlecode.leptonica.android.Pixa;
import com.googlecode.leptonica.android.WriteFile;
import com.googlecode.tesseract.android.TessBaseAPI.PageSegMode;
import com.renard.ocr.R;
import com.renard.ocr.cropimage.MonitoredActivity;
import com.renard.util.Util;

public class OCR extends MonitoredActivity.LifeCycleAdapter {

	private static final String TAG = OCR.class.getSimpleName();

	public static final int MESSAGE_PREVIEW_IMAGE = 3;
	public static final int MESSAGE_END = 4;
	public static final int MESSAGE_ERROR = 5;
	public static final int MESSAGE_TESSERACT_PROGRESS = 6;
	public static final int MESSAGE_FINAL_IMAGE = 7;
	public static final int MESSAGE_UTF8_TEXT = 8;
	public static final int MESSAGE_HOCR_TEXT = 9;
	public static final int MESSAGE_LAYOUT_ELEMENTS = 10;
	public static final int MESSAGE_LAYOUT_PIX = 11;
	public static final int MESSAGE_EXPLANATION_TEXT = 12;
	public static final String EXTRA_WORD_BOX = "word_box";
	public static final String EXTRA_OCR_BOX = "ocr_box";

	static {
		System.loadLibrary("lept");
		System.loadLibrary("tess");
		System.loadLibrary("image_processing_jni");
		nativeInit();

	}

	private Pix pixResult;
	private String ocrResult;
	private String utf8Result;
	private int mPreviewWith;
	private int mPreviewHeight;
	private int mOriginalWidth;
	private int mOriginalHeight;
	private RectF mWordBoundingBox = new RectF();
	private RectF mOCRBoundingBox = new RectF();
	private Messenger mMessenger;
	private boolean mIsActivityAttached = false;
	private ExecutorService mExecutor = Executors.newSingleThreadExecutor();

	protected TessBaseAPI mTess;
	
	public OCR(final MonitoredActivity activity, final Messenger messenger){
		mMessenger = messenger;
		mIsActivityAttached = true;
		activity.addLifeCycleListener(this);
	}

	/**
	 * called from native code
	 *
	 */
	private synchronized void onProgressImage(final int nativePix) {
		Pix preview = new Pix(nativePix);
		if (mMessenger != null && mIsActivityAttached) {
			try {
				Util.savePixToSD(preview,"test"+nativePix);
			} catch (IOException e) {
				e.printStackTrace();
			}
			final Bitmap previewBitmap = WriteFile.writeBitmap(preview);
			mPreviewHeight = preview.getHeight();
			mPreviewWith = preview.getWidth();
			preview.recycle();
			sendMessage(MESSAGE_PREVIEW_IMAGE, previewBitmap);
		} else {
			preview.recycle();
		}

	}


	/**
	 * called from native code
	 * 
	 * @param percent
	 * @param left
	 * @param right
	 * @param top
	 * @param bottom
	 */
	private void onProgressValues(final int percent, final int left, final int right, final int top, final int bottom, final int left2, final int right2, final int top2, final int bottom2) {
		Log.i(TAG,"onProgressValues ("+percent+")");
		int newBottom = (bottom2 - top2) - bottom;
		int newTop = (bottom2 - top2) - top;
		// scale the word bounding rectangle to the preview image space
		float xScale = (1.0f * mPreviewWith) / mOriginalWidth;
		float yScale = (1.0f * mPreviewHeight) / mOriginalHeight;
		mWordBoundingBox.set((left + left2) * xScale, (newTop + top2) * yScale, (right + left2) * xScale, (newBottom + top2) * yScale);
		mOCRBoundingBox.set(left2 * xScale, top2 * yScale, right2 * xScale, bottom2 * yScale);
		Bundle b = new Bundle();
		b.putParcelable(EXTRA_OCR_BOX, mOCRBoundingBox);
		b.putParcelable(EXTRA_WORD_BOX, mWordBoundingBox);
		sendMessage(MESSAGE_TESSERACT_PROGRESS, percent, b);
	}

	/**
	 * 
	 * static const int MESSAGE_IMAGE_DETECTION = 0; static const int
	 * MESSAGE_IMAGE_DEWARP = 1; static const int MESSAGE_OCR = 2; static const
	 * int MESSAGE_ASSEMBLE_PIX = 3; static const int MESSAGE_ANALYSE_LAYOUT =
	 * 4;
	 * 
	 * @param id
	 */

	private void onProgressText(int id) {
		int messageId = 0;
		switch (id) {
		case 0:
			messageId = R.string.progress_image_detection;
			break;
		case 1:
			messageId = R.string.progress_dewarp;
			break;
		case 2:
			messageId = R.string.progress_ocr;
			break;
		case 3:
			messageId = R.string.progress_assemble_pix;
			break;
		case 4:
			messageId = R.string.progress_analyse_layout;
			break;

		}
		if (messageId != 0) {
			sendMessage(MESSAGE_EXPLANATION_TEXT, messageId);
		}
	}

	/**
	 * called from native
	 * 
	 * @param nativePix pix pointer
	 */
	private void onLayoutPix(int nativePix) {
		sendMessage(MESSAGE_LAYOUT_PIX, nativePix);
	}

	/**
	 * called from native
	 * 
	 * @param hocr
	 *            string
	 */
	private void onHOCRResult(String hocr, int accuracy) {
		sendMessage(MESSAGE_HOCR_TEXT, hocr,accuracy);
	}

	/**
	 * called from native
	 * 
	 * @param utf8Text
	 *            string
	 */
	private void onUTF8Result(String utf8Text) {
		sendMessage(MESSAGE_UTF8_TEXT, utf8Text);
	}

	private void onLayoutElements(int nativePixaText, int nativePixaImages) {
		sendMessage(MESSAGE_LAYOUT_ELEMENTS, nativePixaText, nativePixaImages);
	}

	private void sendMessage(int what) {
		sendMessage(what, 0, 0, null, null);
	}
	private void sendMessage(int what, int arg1, int arg2) {
		sendMessage(what, arg1, arg2, null, null);
	}
	private void sendMessage(int what, String string) {
		sendMessage(what, 0, 0, string, null);
	}
    private void sendMessage(int what, String string, int accuracy) {
        sendMessage(what, accuracy, 0, string, null);
    }
	private void sendMessage(int what, int arg1) {
		sendMessage(what, arg1, 0, null, null);
	}
	private void sendMessage(int what, Bitmap previewBitmap) {
		sendMessage(what, 0, 0, previewBitmap, null );		
	}


	private void sendMessage(int what, int arg1, Bundle b) {
		sendMessage(what, arg1, 0, null, b);
	}

	private synchronized void sendMessage(int what, int arg1, int arg2, Object object, Bundle b) {
		if (mIsActivityAttached) {

			Message m = Message.obtain();
			m.what = what;
			m.arg1 = arg1;
			m.arg2 = arg2;
			m.obj = object;
			m.setData(b);
			try {
				mMessenger.send(m);
			} catch (RemoteException ignore) {
				ignore.printStackTrace();
			}
		}
	}

	@Override
	public synchronized void onActivityDestroyed(MonitoredActivity activity) {
		mIsActivityAttached = false;
		cancel();
	}

	@Override
	public synchronized void onActivityResumed(MonitoredActivity activity) {
		mIsActivityAttached = true;
	}

	/**
	 * native code takes care of both Pixa, do not use them after calling this
	 * function pixaText must contain the binary text parts pixaImages must
	 * contain the image parts
	 * 
	 * @param pixaText
	 * @param pixaImages
	 */
	public void startOCRForComplexLayout(final Context context,final String lang, final Pixa pixaText, final Pixa pixaImages, final int[] selectedTexts, final int[] selectedImages) {
		if (pixaText == null) {
			throw new IllegalArgumentException("text pixa must be non-null");
		}

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					final String tessDir = Util.getTessDir(context);
					//TODO fix
					//nativeOCR(pixaText.getNativePixa(), pixaImages.getNativePixa(), selectedTexts, selectedImages, tessDir, lang);
				} finally {
					sendMessage(MESSAGE_END);
				}
			}
		}).start();
	}

	/**
	 * native code takes care of the Pix, do not use it after calling this
	 * function
	 * 
	 * @param pixs
     */
	public void startLayoutAnalysis(final Pix pixs) {

		if (pixs == null) {
			throw new IllegalArgumentException("Source pix must be non-null");
		}

		mOriginalHeight = pixs.getHeight();
		mOriginalWidth = pixs.getWidth();

		new Thread(new Runnable() {
			@Override
			public void run() {
				//TODO fix
					//nativeAnalyseLayout(pixs.getNativePix());
			}
		}).start();
	}

	/**
	 * native code takes care of the Pix, do not use it after calling this
	 * function
	 * 
	 * @param pixs
	 * @param context
	 */
	public void startOCRForSimpleLayout(final Context context, final String lang, final Pix pixs) {
		if (pixs == null) {
			throw new IllegalArgumentException("Source pix must be non-null");
		}

		mOriginalHeight = pixs.getHeight();
		mOriginalWidth = pixs.getWidth();

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					final String tessDir = Util.getTessDir(context);
					long nativeTextPix = nativeOCRBook(pixs.getNativePix());
					pixs.recycle(); 
					Pix pixText = new Pix(nativeTextPix);
			        mOriginalHeight = pixText.getHeight();
			        mOriginalWidth = pixText.getWidth();
					sendMessage(MESSAGE_EXPLANATION_TEXT, R.string.progress_ocr);
					sendMessage(MESSAGE_FINAL_IMAGE, (int)nativeTextPix);

					mTess = new TessBaseAPI();
					boolean result = mTess.init(tessDir, lang);
					if(!result){
						sendMessage(MESSAGE_ERROR);
						return;
					}
					
					mTess.setPageSegMode(PageSegMode.PSM_AUTO);
					mTess.setImage(pixText);
					pixText.recycle();
					String hocrText = mTess.getHOCRText(0);
					String htmlText =  mTess.getHtmlText();
					int accuracy = mTess.meanConfidence();
					sendMessage(MESSAGE_HOCR_TEXT, hocrText,accuracy);
					sendMessage(MESSAGE_UTF8_TEXT, htmlText,accuracy);
						
				} finally {
					sendMessage(MESSAGE_END);
					mTess.end();
				}
			}
		}).start();

	}

	public void cancel() {
		mTess.stop();
	}

	// ***************
	// * NATIVE CODE *
	// ***************

	private static native void nativeInit();

	private native long nativeOCRBook(long nativePix);

	//private native long nativeOCR(long nativePixaTexts, long nativePixaImages, int[] selectedTexts, int[] selectedImages, String tessDir, String lang);

	//private native long nativeAnalyseLayout(long nativePix);

}