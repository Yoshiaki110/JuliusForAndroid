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
//	private static final String GRAMMAR_JCONF = "/julius/demo-grammar-android.jconf";
	private static final String WAVE_PATH = "/julius/voice.wav";
	private static final int SAMPLING_RATE = 22050;
	
	static {
		System.loadLibrary("julius_arm");
	}
	private native boolean initJulius(String jconfpath);
	private native void recognize(String wavpath);
	private native void terminateJulius();

	private boolean isInitialized = false;	// initJulius()に成功したらtrue

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
			Log.d(ITO, "terminateJulius() 開始");
			terminateJulius();
			Log.d(ITO, "terminateJulius() 終了");
			isInitialized = false;
		}
		super.onDestroy();
	}

	// Juliusの初期化を別スレッドで実行
	private class JuliusInitializer extends AsyncTask<Integer, Void, Boolean> {
		private ProgressDialog progressDialog;
		Context context;

		public JuliusInitializer(Context context) {
			this.context = context;
		}

		// メインスレッドで実行
		@Override
		protected void onPreExecute() {
			Log.d(TAG, "JuliusInitializer:onPreExecute");
			progressDialog = new ProgressDialog(context);
			progressDialog.setMessage(JuliusActivity.this.getString(R.string.initializing_message));
			progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
			progressDialog.show();
		}

		// メインスレッドとは別のスレッドで実行
		@Override
		protected Boolean doInBackground(Integer... params) {
			if (isInitialized) {
				Log.d(ITO, "terminateJulius() 開始");
				terminateJulius();
				Log.d(ITO, "terminateJulius() 終了");
			}
			String conf;
			Log.d(TAG, "JuliusInitializer:doInBackground:conf is continuous");
			conf = CONTINUOUS_JCONF;

			Log.d(ITO, "initJulius() 開始");
			if (initJulius(Environment.getExternalStorageDirectory() + conf)) {
				Log.d(TAG, "JuliusInitializer:doInBackground:init julius success");
				Log.d(ITO, "initJulius() 終了");
				return true;
			} else {
				Log.e(TAG, "JuliusInitializer:doInBackground:init julius error");
				Log.d(ITO, "initJulius() エラー終了");
				return false;
			}
		}

		// doInBackgroundメソッドの実行後にメインスレッドで実行
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
			} else {
				Log.d(TAG, "call recognize");
				isRecording = false;			// レコード中のループを抜ける
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
				Log.d(ITO, "ファイル書き込み 開始");
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
				Log.d(ITO, "ファイル書き込み 終了");
				Log.d(TAG, "end recording");
			}
		};
	};

	// Juliusを別スレッドで実行
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
			progressDialog.show();			// プログレスダイアログを表示する
		}

		@Override
		protected Void doInBackground(String... params) {
			String wavepath = params[0];
			Log.d(ITO, "recognize() 開始");
			recognize(wavepath);
			Log.d(ITO, "recognize() 終了");
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			Log.d(TAG, "JuliusRecognizer:onPostExecute");
			progressDialog.dismiss();		// プログレスダイアログを閉じる
			TextView resultView = (TextView) findViewById(R.id.result_text);
			resultView.setText(resultStr);
			button.setText(R.string.speech);
			button.setEnabled(true);
		}
	}

	// 使っていない
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
/*
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class Test {
	public static void main(String[] args) throws Exception {
		Test obj = new Test();
		obj.main();
	}

	private boolean _recording = false;

	public void main() throws Exception {
		long silent = 0L;
		int filename = 0;
		while (true) {
			int n = get();
			if (n >= 9) {
				break;
			}
			if (n > 4) {
				_recording = true;
				silent = 0L;
			} else {
				if (_recording == true) {
					long now = System.currentTimeMillis();
					if (silent == 0L) {
						silent = now;
					} else {
						if (now - silent > 5000) {
							_recording = false;
							silent = 0L;
							++filename;
							System.out.println("\t\t\tfilename:" + filename);
						}
					}
				}
			}
			System.out.println(n + " recording:" + _recording);
		}
	}

	public int get() throws Exception {
		FileReader filereader = null;
		int ret = -1;
		try {
			File file = new File("c:\\tmp\\test.txt");
			filereader = new FileReader(file);
			ret = filereader.read() - 48;
		} catch (FileNotFoundException e) {
			System.out.println(e);
		} catch (IOException e) {
			System.out.println(e);
		} finally {
			if (filereader != null) {
				filereader.close();
			}
		}
		return ret;
	}
}

*/
