package com.fy.recorder;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.fy.baselibrary.utils.FileUtils;
import com.fy.baselibrary.utils.notify.L;
import com.fy.recorder.fftlib.ByteUtils;
import com.fy.recorder.fftlib.FftFactory;
import com.fy.recorder.listener.RecordDataListener;
import com.fy.recorder.listener.RecordFftDataListener;
import com.fy.recorder.listener.RecordResultListener;
import com.fy.recorder.listener.RecordSoundSizeListener;
import com.fy.recorder.listener.RecordStateListener;
import com.fy.recorder.mp3.Mp3EncodeThread;
import com.fy.recorder.wav.WavUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 录音帮助类
 * Created by fangs on 2020/2/17 13:54.
 */
public class RecordHelper {
    private static final String TAG = RecordHelper.class.getSimpleName();
    private volatile static RecordHelper instance;
    private volatile RecordState state = RecordState.IDLE;
    private static final int RECORD_AUDIO_BUFFER_TIMES = 1;

    private RecordStateListener recordStateListener;
    private RecordDataListener recordDataListener;
    private RecordSoundSizeListener recordSoundSizeListener;
    private RecordResultListener recordResultListener;
    private RecordFftDataListener recordFftDataListener;

    private RecordConfig currentConfig;
    private AudioRecordThread audioRecordThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private long recordTime;//已经录制时间
    private File resultFile = null;
    private File tmpFile = null;
    private List<File> files = new ArrayList<>();
    private Mp3EncodeThread mp3EncodeThread;

    private RecordHelper() {
    }

    static RecordHelper getInstance() {
        if (instance == null) {
            synchronized (RecordHelper.class) {
                if (instance == null) {
                    instance = new RecordHelper();
                }
            }
        }
        return instance;
    }

    RecordState getState() {
        return state;
    }

    void setRecordStateListener(RecordStateListener recordStateListener) {
        this.recordStateListener = recordStateListener;
    }

    void setRecordDataListener(RecordDataListener recordDataListener) {
        this.recordDataListener = recordDataListener;
    }

    void setRecordSoundSizeListener(RecordSoundSizeListener recordSoundSizeListener) {
        this.recordSoundSizeListener = recordSoundSizeListener;
    }

    void setRecordResultListener(RecordResultListener recordResultListener) {
        this.recordResultListener = recordResultListener;
    }

    public void setRecordFftDataListener(RecordFftDataListener recordFftDataListener) {
        this.recordFftDataListener = recordFftDataListener;
    }

    public void start(String filePath, RecordConfig config) {
        this.currentConfig = config;
        if (state != RecordState.IDLE && state != RecordState.STOP) {
            L.e(TAG, "状态异常当前状态： %s"+ state.name());
            return;
        }
        resultFile = new File(filePath);
        String tempFilePath = FileUtils.createFile(FileUtils.record, "Record_temp_", ".pcm", 2).getPath();

        L.d(TAG, "----------------开始录制 %s------------------------" + currentConfig.getFormat().name());
        L.d(TAG, "参数： %s" + currentConfig.toString());
        L.i(TAG, "pcm缓存 tmpFile: %s"+ tempFilePath);
        L.i(TAG, "录音文件 resultFile: %s"+ filePath);


        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    public void stop() {
        if (state == RecordState.IDLE) {
            L.e(TAG, "状态异常当前状态： %s" + state.name());
            return;
        }

        if (state == RecordState.PAUSE) {
            makeFile();
            state = RecordState.IDLE;
            notifyState();
            stopMp3Encoded();
        } else {
            state = RecordState.STOP;
            notifyState();
        }
    }

    public void cancel() {
        stop();
        FileUtils.deleteFileSafely(resultFile);
    }

    void pause() {
        if (state != RecordState.RECORDING) {
            L.e(TAG, "状态异常当前状态： %s"+ state.name());
            return;
        }
        state = RecordState.PAUSE;
        notifyState();
    }

    void resume() {
        if (state != RecordState.PAUSE) {
            L.e(TAG, "状态异常当前状态： %s" + state.name());
            return;
        }
        String tempFilePath = FileUtils.createFile(FileUtils.record, "Record_temp_", ".pcm", 2).getPath();
        L.i(TAG, "tmpPCM File: %s" + tempFilePath);
        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    private void notifyState() {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onStateChange(state);
            }
        });

        if (state == RecordState.STOP || state == RecordState.PAUSE) {
            if (recordSoundSizeListener != null) {
                recordSoundSizeListener.onSoundSize(0, recordTime);
            }
        }
    }

    private void notifyFinish() {
        L.d(TAG, "录音结束 file: %s" + resultFile.getAbsolutePath());

        FileUtils.deleteFileSafely(tmpFile);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordStateListener != null) {
                    recordStateListener.onStateChange(RecordState.FINISH);
                }
                if (recordResultListener != null) {
                    recordResultListener.onResult(resultFile);
                }
            }
        });
    }

    private void notifyError(final String error) {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onError(error);
            }
        });
    }

    private FftFactory fftFactory = new FftFactory(FftFactory.Level.Original);

    private void notifyData(final byte[] data, final long recordTime) {
        if (recordDataListener == null && recordSoundSizeListener == null && recordFftDataListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordDataListener != null) {
                    recordDataListener.onData(data);
                }

                if (recordFftDataListener != null || recordSoundSizeListener != null) {
                    byte[] fftData = fftFactory.makeFftData(data);
                    if (fftData != null) {
                        if (recordSoundSizeListener != null) {
                            recordSoundSizeListener.onSoundSize(getDb(fftData), recordTime);
                        }
                        if (recordFftDataListener != null) {
                            recordFftDataListener.onFftData(fftData);
                        }
                    }
                }
            }
        });
    }

    private int getDb(byte[] data) {
        double sum = 0;
        double ave;
        int length = data.length > 128 ? 128 : data.length;
        int offsetStart = 8;
        for (int i = offsetStart; i < length; i++) {
            sum += data[i];
        }
        ave = (sum / (length - offsetStart)) * 65536 / 128f;
        int i = (int) (Math.log10(ave) * 20);
        return i < 0 ? 27 : i;
    }

    private void initMp3EncoderThread(int bufferSize) {
        try {
            mp3EncodeThread = new Mp3EncodeThread(resultFile, bufferSize);
            mp3EncodeThread.start();
        } catch (Exception e) {
            L.e(TAG, e.getMessage());
        }
    }

    private class AudioRecordThread extends Thread {
        private AudioRecord audioRecord;
        private int bufferSize;
        private long startTime = System.currentTimeMillis();

        AudioRecordThread() {
            bufferSize = AudioRecord.getMinBufferSize(currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig()) * RECORD_AUDIO_BUFFER_TIMES;
            L.d(TAG, "record buffer size = %s--" + bufferSize);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig(), bufferSize);
            if (currentConfig.getFormat() == RecordConfig.RecordFormat.MP3) {
                if (mp3EncodeThread == null) {
                    initMp3EncoderThread(bufferSize);
                } else {
                    L.e(TAG, "mp3EncodeThread != null, 请检查代码");
                }
            }
        }

        @Override
        public void run() {
            super.run();

            switch (currentConfig.getFormat()) {
                case MP3:
                    startMp3Recorder();
                    break;
                default:
                    startPcmRecorder();
                    break;
            }
        }

        private void startPcmRecorder() {
            state = RecordState.RECORDING;
            notifyState();
            L.d(TAG, "开始录制 Pcm");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tmpFile);
                audioRecord.startRecording();
                byte[] byteBuffer = new byte[bufferSize];

                while (state == RecordState.RECORDING) {
                    recordTime = System.currentTimeMillis() - startTime;//已经录制时间
                    if (recordTime > currentConfig.getRecordMaxTime()){//录制时间达到最大
                        break;
                    }

                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    notifyData(byteBuffer, recordTime);
                    fos.write(byteBuffer, 0, end);
                    fos.flush();
                }
                audioRecord.stop();
                files.add(tmpFile);
                if (state == RecordState.STOP) {
                    makeFile();
                } else {
                    L.i(TAG, "暂停！");
                }
            } catch (Exception e) {
                L.e(TAG, e.getMessage());
                notifyError("录音失败");
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
                L.d(TAG, "录音结束");
            }
        }

        private void startMp3Recorder() {
            state = RecordState.RECORDING;
            notifyState();

            try {
                audioRecord.startRecording();
                short[] byteBuffer = new short[bufferSize];

                while (state == RecordState.RECORDING) {
                    recordTime = System.currentTimeMillis() - startTime;
                    if (recordTime > currentConfig.getRecordMaxTime()){//录制时间达到最大
                        break;
                    }

                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    if (mp3EncodeThread != null) {
                        mp3EncodeThread.addChangeBuffer(new Mp3EncodeThread.ChangeBuffer(byteBuffer, end));
                    }
                    notifyData(ByteUtils.toBytes(byteBuffer), recordTime);
                }
                audioRecord.stop();
            } catch (Exception e) {
                L.e(TAG, e.getMessage());
                notifyError("录音失败");
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
                stopMp3Encoded();
            } else {
                L.d(TAG, "暂停");
            }
        }
    }

    private void stopMp3Encoded() {
        if (mp3EncodeThread != null) {
            mp3EncodeThread.stopSafe(new Mp3EncodeThread.EncordFinishListener() {
                @Override
                public void onFinish() {
                    notifyFinish();
                    mp3EncodeThread = null;
                }
            });
        } else {
            L.e(TAG, "mp3EncodeThread is null, 代码业务流程有误，请检查！！ ");
        }
    }

    private void makeFile() {
        switch (currentConfig.getFormat()) {
            case MP3:
                return;
            case WAV:
                mergePcmFile();
                makeWav();
                break;
            case PCM:
                mergePcmFile();
                break;
            default:
                break;
        }
        notifyFinish();
        L.i("录音完成！ path: %s ； 大小：%s", resultFile.getAbsoluteFile() + "--" + resultFile.length());
    }

    /**
     * 添加Wav头文件
     */
    private void makeWav() {
        if (!FileUtils.fileIsExist(resultFile.getPath()) || resultFile.length() == 0) {
            return;
        }
        byte[] header = WavUtils.generateWavFileHeader((int) resultFile.length(), currentConfig.getSampleRate(), currentConfig.getChannelCount(), currentConfig.getEncoding());
        WavUtils.writeHeader(resultFile, header);
    }

    /**
     * 合并文件
     */
    private void mergePcmFile() {
        boolean mergeSuccess = mergePcmFiles(resultFile, files);
        if (!mergeSuccess) {
            notifyError("合并失败");
        }
    }

    /**
     * 合并Pcm文件
     *
     * @param recordFile 输出文件
     * @param files      多个文件源
     * @return 是否成功
     */
    private boolean mergePcmFiles(File recordFile, List<File> files) {
        if (recordFile == null || files == null || files.size() <= 0) {
            return false;
        }

        FileOutputStream fos = null;
        BufferedOutputStream outputStream = null;
        byte[] buffer = new byte[1024];
        try {
            fos = new FileOutputStream(recordFile);
            outputStream = new BufferedOutputStream(fos);

            for (int i = 0; i < files.size(); i++) {
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(files.get(i)));
                int readCount;
                while ((readCount = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, readCount);
                }
                inputStream.close();
            }
        } catch (Exception e) {
            L.e(TAG, e.getMessage());
            return false;
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i).delete();
        }
        files.clear();
        return true;
    }

    /**
     * 表示当前状态
     */
    public enum RecordState {
        /**
         * 空闲状态
         */
        IDLE,
        /**
         * 录音中
         */
        RECORDING,
        /**
         * 暂停中
         */
        PAUSE,
        /**
         * 正在停止
         */
        STOP,
        /**
         * 录音流程结束（转换结束）
         */
        FINISH
    }

}
