package jp.co.tis.stc.julius;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class JuliusActivity extends Activity {
	private static final String TAG = "Julius JulisuActivity";
	private static final String ITO = "ito";
	private static final String CONTINUOUS_JCONF = "/julius/fast-android.jconf";
///	private static final String GRAMMAR_JCONF = "/julius/demo-grammar-android.jconf";
	private static final String WAVE_PATH = "/julius/voice.wav";
	private static final int SAMPLING_RATE = 22050;
	
	static {
		System.loadLibrary("julius_arm");
	}
	private native boolean initJulius(String jconfpath);
	private native void recognize(String wavpath);
	private native void terminateJulius();

	private boolean isInitialized = false;

	private AudioRecord audioRec = null;
	private int bufSize = 0;
	private String resultStr = "";
	private TextView resultText;
	private Button button;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_julius);
		resultText = (TextView) findViewById(R.id.result_text);

		bufSize = AudioRecord.getMinBufferSize(SAMPLING_RATE,
				AudioFormat.CHANNEL_CONFIGURATION_MONO,
				AudioFormat.ENCODING_PCM_16BIT) * 2;
		audioRec = new AudioRecord(MediaRecorder.AudioSource.MIC,
				SAMPLING_RATE, AudioFormat.CHANNEL_CONFIGURATION_MONO,
				AudioFormat.ENCODING_PCM_16BIT, bufSize);

		new JuliusInitializer(JuliusActivity.this).execute();

		button = (Button) findViewById(R.id.speech_button);
		button.setEnabled(false);
		button.setOnClickListener(onClickListener);
	}

	@Override
	protected void onDestroy() {
		if (isInitialized) {
			Log.d(ITO, "terminateJulius() �J�n");
			terminateJulius();
			Log.d(ITO, "terminateJulius() �I��");
			isInitialized = false;
		}
		super.onDestroy();
	}

	private class JuliusInitializer extends AsyncTask<Integer, Void, Boolean> {
		private ProgressDialog progressDialog;
		Context context;

		public JuliusInitializer(Context context) {
			this.context = context;
		}

		@Override
		protected void onPreExecute() {
			Log.d(TAG, "JuliusInitializer:onPreExecute");
			progressDialog = new ProgressDialog(context);
			progressDialog.setMessage(JuliusActivity.this.getString(R.string.initializing_message));
			progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			progressDialog.show();
		}

		@Override
		protected Boolean doInBackground(Integer... params) {
			if (isInitialized) {
				Log.d(ITO, "terminateJulius() �J�n");
				terminateJulius();
				Log.d(ITO, "terminateJulius() �I��");
			}
			String conf;
			Log.d(TAG, "JuliusInitializer:doInBackground:conf is continuous");
			conf = CONTINUOUS_JCONF;

			Log.d(ITO, "initJulius() �J�n");
			if (initJulius(Environment.getExternalStorageDirectory() + conf)) {
				Log.d(TAG, "JuliusInitializer:doInBackground:init julius success");
				Log.d(ITO, "initJulius() �I��");
				return true;
			} else {
				Log.e(TAG, "JuliusInitializer:doInBackground:init julius error");
				Log.d(ITO, "initJulius() �G���[�I��");
				return false;
			}
		}

		@Override
		protected void onPostExecute(Boolean result) {
			Log.d(TAG, "JuliusInitializer:onPostExecute");
			progressDialog.dismiss();
			if (result) {
				isInitialized = true;
				button.setEnabled(true);
			} else {
				isInitialized = false;
				button.setEnabled(false);
				Toast.makeText(context, "initJulius Error", Toast.LENGTH_LONG).show();
			}
		}
	}

	private final View.OnClickListener onClickListener = new View.OnClickListener() {
		private boolean isRecording = false;
		private Thread writeAudioToFileThread = null;
		
		@Override
		public void onClick(View v) {
			if (!isRecording) {
				Log.d(TAG, "start recording");
				isRecording = true;
				writeAudioToFileThread = new Thread(writeAudioToFile);
				button.setText(R.string.recording);
				resultText.setText(JuliusActivity.this.getString(R.string.init_text));
				audioRec.startRecording();
				writeAudioToFileThread.start();
			}
			else {
				Log.d(TAG, "call recognize");
				isRecording = false;
				try {
					writeAudioToFileThread.join();
				} catch (InterruptedException e) {
					Log.e(TAG, e.toString());
				}
				button.setText(R.string.recogninzing);
				button.setEnabled(false);
				new JuliusRecognizer(JuliusActivity.this).execute(Environment.getExternalStorageDirectory() + WAVE_PATH);
			}
		}
		private final Runnable writeAudioToFile = new Runnable() {
			@Override
			public void run() {
				Log.d(ITO, "�t�@�C���������� �J�n");
				android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
				File recFile = new File(Environment.getExternalStorageDirectory() + WAVE_PATH);
				FileOutputStream fout = null;
				DataOutputStream dout = null;
				try {
					if (recFile.exists()) {
						recFile.delete();
					}
					recFile.createNewFile();
					fout = new FileOutputStream(recFile);
					dout = new DataOutputStream(fout);
				
					short buf[] = new short[bufSize];
					int cnt = 0;
					long ls = 0L;
					long lrs = 0L;
					Log.d(TAG, "******* start");
					while (isRecording) {
						audioRec.read(buf, 0, buf.length);
						for (short s : buf) {
							short rs = Short.reverseBytes(s);
							ls += Math.abs(s);
							lrs += Math.abs(rs);
							dout.writeShort(rs);
							if (++cnt >= 10000) {
								Log.d(TAG, "******* " + ls + " " + lrs);
								cnt = 0;
								ls = 0;
								lrs = 0;
							}
						}
					}
					audioRec.stop();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				} finally {
					try {
						dout.close();
						fout.close();
					} catch (IOException e) {
						Log.e(TAG, e.toString());
					}
				}
				Log.d(ITO, "�t�@�C���������� �I��");
				Log.d(TAG, "end recording");
			}
		};
	};

	private class JuliusRecognizer extends AsyncTask<String, Void, Void> {
		private ProgressDialog progressDialog;
		Context context;

		public JuliusRecognizer(Context context) {
			this.context = context;
		}

		@Override
		protected void onPreExecute() {
			Log.d(TAG, "JuliusRecognizer:onPreExecute");
			progressDialog = new ProgressDialog(context);
			progressDialog.setMessage(JuliusActivity.this.getString(R.string.recognizing_message));
			progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			progressDialog.show();
		}

		@Override
		protected Void doInBackground(String... params) {
			String wavepath = params[0];
			Log.d(ITO, "recognize() start");
			recognize(wavepath);
			Log.d(ITO, "recognize() end");
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			Log.d(TAG, "JuliusRecognizer:onPostExecute");
			progressDialog.dismiss();
			TextView resultView = (TextView) findViewById(R.id.result_text);
			resultView.setText(resultStr);
			button.setText(R.string.speech);
			button.setEnabled(true);
		}
	}

	public void callback(byte[] result) {
		Log.d(TAG, "callbacked");
		StringBuilder bld = new StringBuilder();
		for (byte b : result) {
			bld.append(String.format("%02x ", b));
		}
		Log.d(TAG, "result:" + bld.toString());

		try {
			resultStr = new String(result, "Shift_JIS");
		} catch (UnsupportedEncodingException e) {
			Log.e(TAG, e.toString());
		}
		Log.d(TAG, "callbacked " + resultStr);
	}
}
