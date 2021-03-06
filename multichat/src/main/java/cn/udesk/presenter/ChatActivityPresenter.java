package cn.udesk.presenter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Message;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.Callback;
import com.lzy.okgo.model.HttpParams;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.base.Request;

import org.apache.http.conn.ConnectTimeoutException;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import cn.udesk.JsonUtils;
import cn.udesk.R;
import cn.udesk.UdeskConst;
import cn.udesk.UdeskSDKManager;
import cn.udesk.UdeskUtil;
import cn.udesk.activity.UdeskChatActivity.MessageWhat;
import cn.udesk.adapter.UDEmojiAdapter;
import cn.udesk.config.UdeskConfig;
import cn.udesk.model.InitMode;
import cn.udesk.model.Merchant;
import cn.udesk.model.SendMsgResult;
import cn.udesk.muchat.HttpCallBack;
import cn.udesk.muchat.HttpFacade;
import cn.udesk.muchat.UdeskLibConst;
import cn.udesk.muchat.bean.AliBean;
import cn.udesk.muchat.bean.ExtrasInfo;
import cn.udesk.muchat.bean.Products;
import cn.udesk.muchat.bean.ReceiveMessage;
import cn.udesk.muchat.bean.SendMessage;
import cn.udesk.voice.AudioRecordState;
import cn.udesk.voice.AudioRecordingAacThread;
import cn.udesk.voice.VoiceRecord;
import cn.udesk.xmpp.Concurrents;
import udesk.core.utils.UdeskIdBuild;
import udesk.core.utils.UdeskUtils;

public class ChatActivityPresenter {

    private IChatActivityView mChatView;
    private VoiceRecord mVoiceRecord = null;
    private String mRecordTmpFile = "";
    int failureCount;

    public ChatActivityPresenter(IChatActivityView chatview) {
        this.mChatView = chatview;

    }

    public void unBind() {
        mChatView = null;
    }


    /**
     * 收到新消息
     */
    public void onNewMessage(ReceiveMessage msgInfo) {

        try {
            if (mChatView != null && mChatView.getHandler() != null) {
                Message messge = mChatView.getHandler().obtainMessage(
                        MessageWhat.onNewMessage);
                messge.obj = msgInfo;
                mChatView.getHandler().sendMessage(messge);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //发送商品链接广告
    public void sendCommodity(final Products products) {
        InitMode initMode = UdeskSDKManager.getInstance().getInitMode();
        if (initMode != null) {
            HttpFacade.getInstance().sendProducts(UdeskUtil.getAuthToken(UdeskUtil.objectToString(initMode.getIm_username()),
                    UdeskUtil.objectToString(initMode.getIm_password())), mChatView.getEuid(), products, new HttpCallBack() {
                @Override
                public void onSuccess(String message) {
                    if (UdeskLibConst.isDebug) {
                        Log.i("udesk", "sendCommodity result =" + message);
                    }
                }

                @Override
                public void onFail(Throwable message) {
                    failureCount++;
                    if (failureCount < 3) {
                        sendCommodity(products);
                    }
                    if (message instanceof ConnectTimeoutException) {
                        if (UdeskLibConst.isDebug) {
                            Log.i("udesk", "sendCommodity result =" + mChatView.getContext().getString(R.string.time_out));
                        }
                    }
                }

                @Override
                public void onSuccessFail(String message) {
                    if (UdeskLibConst.isDebug) {
                        Log.i("udesk", "sendCommodity result =" + message);
                    }
                }
            });
        }

    }


    //发送文本消息
    public void sendTxtMessage() {
        try {
            if (!TextUtils.isEmpty(mChatView.getInputContent().toString().trim())) {
                sendTxtMessage(mChatView.getInputContent().toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //封装发送文本消息
    public void sendTxtMessage(String msgString) {
        try {
            ReceiveMessage receiveMessage = buildSendMessage(UdeskConst.ChatMsgTypeString.TYPE_TEXT, msgString);
            mChatView.clearInputContent();
            onNewMessage(receiveMessage);
            createMessage(UdeskUtil.objectToString(receiveMessage.getId()), msgString, UdeskConst.ChatMsgTypeString.TYPE_TEXT, receiveMessage.getExtras());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // 发送录音信息
    public void sendRecordAudioMsg(String audiopath, long duration) {
        try {
            ReceiveMessage receiveMessage = buildSendMessage(UdeskConst.ChatMsgTypeString.TYPE_AUDIO, "", audiopath);
            duration = duration / 1000 + 1;
            ExtrasInfo info = new ExtrasInfo();
            info.setDuration(duration);
            receiveMessage.setExtras(info);
            onNewMessage(receiveMessage);
            upLoadFile(audiopath, receiveMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //发送图片消息
    public void sendBitmapMessage(String photoPath) {
        try {
            if (TextUtils.isEmpty(photoPath)) {
                UdeskUtils.showToast(mChatView.getContext(), mChatView.getContext().getString(R.string.udesk_upload_img_error));
                return;
            }
            ReceiveMessage receiveMessage = buildSendMessage(UdeskConst.ChatMsgTypeString.TYPE_IMAGE, "", photoPath);
            onNewMessage(receiveMessage);
            upLoadFile(photoPath, receiveMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ReceiveMessage buildSendMessage(String msgtype, String content) {
        return buildSendMessage(msgtype, content, "");
    }

    //构建消息模型
    public ReceiveMessage buildSendMessage(String msgtype, String content, String locationPath) {
        ReceiveMessage msg = new ReceiveMessage();
        msg.setContent_type(msgtype);
        msg.setId(UdeskIdBuild.buildMsgId());
        msg.setMerchant_euid(mChatView.getEuid());
        msg.setDirection(UdeskConst.ChatMsgDirection.Send);
        msg.setSendFlag(UdeskConst.SendFlag.RESULT_SEND);
        msg.setReadFlag(UdeskConst.ChatMsgReadFlag.read);
        msg.setContent(content);
        msg.setLocalPath(locationPath);
        return msg;
    }

    /**
     * 表情28个,最后一个标签显示删除了，只显示了27个
     *
     * @param id
     * @param emojiCount
     * @param emojiString
     */
    public void clickEmoji(long id, int emojiCount, String emojiString) {
        try {
            if (id == (emojiCount - 1)) {
                String str = mChatView.getInputContent().toString();
                CharSequence text = mChatView.getInputContent();
                int selectionEnd = Selection.getSelectionEnd(text);
                String string = str.substring(0, selectionEnd);
                if (string.length() > 0) {

                    String deleteLastEmotion = deleteLastEmotion(string);
                    if (deleteLastEmotion.length() > 0) {

                        mChatView.refreshInputEmjio(deleteLastEmotion
                                + str.substring(selectionEnd));
                    } else {
                        mChatView.refreshInputEmjio(""
                                + str.substring(selectionEnd));
                    }
                    CharSequence c = mChatView.getInputContent();
                    if (c instanceof Spannable) {
                        Spannable spanText = (Spannable) c;
                        Selection
                                .setSelection(spanText, deleteLastEmotion.length());
                    }
                }
            } else {
                CharSequence text = mChatView.getInputContent();
                int selectionEnd = Selection.getSelectionEnd(text);
                String editString = text.toString().substring(0, selectionEnd)
                        + emojiString + text.toString().substring(selectionEnd);
                mChatView.refreshInputEmjio(editString);
                CharSequence c = mChatView.getInputContent();
                if (c instanceof Spannable) {
                    Spannable spanText = (Spannable) c;
                    Selection.setSelection(spanText,
                            selectionEnd + emojiString.length());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //删除表情
    private String deleteLastEmotion(String str) {
        try {
            if (TextUtils.isEmpty(str)) {
                return "";
            }
            try {
                List<String> emotionList = mChatView.getEmotionStringList();
                int lastIndexOf = str.lastIndexOf(UDEmojiAdapter.EMOJI_PREFIX);
                if (lastIndexOf > -1) {
                    String substring = str.substring(lastIndexOf);
                    boolean contains = emotionList.contains(substring);
                    if (contains) {
                        return str.substring(0, lastIndexOf);
                    }
                }
                return str.substring(0, str.length() - 1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    // 开始录音
    public void recordStart() {
        // HorVoiceView负责界面。AudioRecordingAacThread负责具体录音。RecordTouchListener则负责手势判断
        // 在此之前，请确保SD卡是可以使用的
        // 后台录音开始
        try {
            mVoiceRecord = new AudioRecordingAacThread();
            mRecordTmpFile = UdeskUtil.getOutputAudioPath(mChatView.getContext());
            mVoiceRecord.initResource(mRecordTmpFile, new AudioRecordState() {
                @Override
                public void onRecordingError() {
                    if (mChatView.getHandler() != null) {
                        mChatView.getHandler().sendEmptyMessage(
                                MessageWhat.RECORD_ERROR);
                    }
                }

                @Override
                public void onRecordSuccess(final String resultFilePath,
                                            long duration) {
                    mChatView.onRecordSuccess(resultFilePath, duration);
                }

                @Override
                public void onRecordSaveError() {
                }

                @Override
                public void onRecordTooShort() {
                    if (mChatView.getHandler() != null) {
                        mChatView.getHandler().sendEmptyMessage(
                                MessageWhat.RECORD_Too_Short);
                    }
                }

                @Override
                public void onRecordCancel() {

                }

                @Override
                public void updateRecordState(int micAmplitude) {

                    if (mChatView.getHandler() != null) {
                        Message message = mChatView.getHandler().obtainMessage(
                                MessageWhat.UPDATE_VOCIE_STATUS);
                        message.arg1 = micAmplitude;
                        mChatView.getHandler().sendMessage(message);
                    }
                }

                @Override
                public void onRecordllegal() {
                    // 停止录音，提示开取录音权限
                    if (mChatView.getHandler() != null) {
                        mChatView.getHandler().sendEmptyMessage(
                                MessageWhat.recordllegal);
                    }

                }
            });
            mVoiceRecord.startRecord();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void doRecordStop(boolean isCancel) {
        // 结束后台录音功能
        try {
            if (mVoiceRecord != null) {
                if (isCancel) {
                    mVoiceRecord.cancelRecord();

                } else {
                    mVoiceRecord.stopRecord();
                }
                mVoiceRecord = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void upLoadFile(final String filePath, final ReceiveMessage receiveMessage) {
        try {

            InitMode initMode = UdeskSDKManager.getInstance().getInitMode();
            if (initMode != null) {

                HttpFacade.getInstance().getAliInfo(UdeskUtil.getAuthToken(UdeskUtil.objectToString(initMode.getIm_username()),
                        UdeskUtil.objectToString(initMode.getIm_password())), new HttpCallBack() {
                    @Override
                    public void onSuccess(final String message) {

                        if (UdeskLibConst.isDebug) {
                            Log.i("udesk", "getAliInfo result =" + message);
                        }
                        File file = new File(filePath);
                        String fileName = file.getName();
                        final AliBean aliBean = JsonUtils.parseAlInfo(message);
                        final String alikey =UdeskUtil.objectToString(aliBean.getPrefix())+"/"+fileName;
                        String endpoint = UdeskUtil.objectToString(aliBean.getEndpoint());
                        if (!endpoint.contains("http")){
                            endpoint = "https://"+ UdeskUtil.objectToString(aliBean.getBucket())+"."+endpoint;
                        }
                        final String uploadurl = endpoint+"/"+alikey;
                        OkGo.<String>post(endpoint).isMultipart(true).params("file", new File(filePath))
                                .execute(new Callback<String>() {
                                    @Override
                                    public void onStart(Request<String, ? extends Request> request) {

                                        HttpParams params = new HttpParams();
                                        try {
                                            params.put("OSSAccessKeyId", UdeskUtil.objectToString(aliBean.getAccess_id()));
                                            params.put("bucket",  UdeskUtil.objectToString(aliBean.getBucket()));
                                            params.put("policy", UdeskUtil.objectToString(aliBean.getPolicy_Base64()));
                                            params.put("Signature", UdeskUtil.objectToString(aliBean.getSignature()));
                                            params.put("key", alikey);
                                            params.put("expire", UdeskUtil.objectToString(aliBean.getExpire_at()));
                                            params.put("file", new File(filePath));
                                            request.getParams().put(params);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public void onSuccess(Response<String> response) {

                                    }

                                    @Override
                                    public void onCacheSuccess(Response<String> response) {

                                    }

                                    @Override
                                    public void onError(Response<String> response) {
                                        sendMessageResult(UdeskUtil.objectToString(receiveMessage.getId()), UdeskConst.SendFlag.RESULT_FAIL, new SendMsgResult());
                                    }

                                    @Override
                                    public void onFinish() {

                                    }

                                    @Override
                                    public void uploadProgress(Progress progress) {

                                    }

                                    @Override
                                    public void downloadProgress(Progress progress) {

                                    }

                                    @Override
                                    public String convertResponse(okhttp3.Response response) throws Throwable {
                                        createMessage(UdeskUtil.objectToString(receiveMessage.getId()), uploadurl, UdeskUtil.objectToString(receiveMessage.getContent_type()), receiveMessage.getExtras());
                                        return null;
                                    }
                                });
                    }

                    @Override
                    public void onFail(Throwable message) {

                    }

                    @Override
                    public void onSuccessFail(String message) {
                        if (UdeskLibConst.isDebug) {
                            Log.i("udesk", "getAliInfo result =" + message);
                        }
                    }
                });

            }
        } catch (Exception e) {
            e.printStackTrace();
        } catch (OutOfMemoryError error) {
            error.printStackTrace();
        }

    }



    //点击失败按钮 重试发送消息
    public void startRetryMsg(ReceiveMessage message) {
        try {
            if (UdeskUtil.objectToString(message.getContent_type()).equals(UdeskConst.ChatMsgTypeString.TYPE_TEXT)) {
                createMessage(UdeskUtil.objectToString(message.getId()), UdeskUtil.objectToString(message.getContent()), UdeskUtil.objectToString(message.getContent_type()), message.getExtras());
            } else if (UdeskUtil.objectToString(message.getContent_type()).equals(UdeskConst.ChatMsgTypeString.TYPE_AUDIO)
                    || UdeskUtil.objectToString(message.getContent_type()).equals(UdeskConst.ChatMsgTypeString.TYPE_IMAGE) ) {

                if (!UdeskUtil.objectToString(message.getLocalPath()).isEmpty()) {
                    upLoadFile(UdeskUtil.objectToString(message.getLocalPath()), message);

                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return;
    }


    //获取商户详情
    public void getMerchantInfo() {
        InitMode initMode = UdeskSDKManager.getInstance().getInitMode();
        if (initMode != null) {
            HttpFacade.getInstance().getMerchantsDetails(UdeskUtil.getAuthToken(UdeskUtil.objectToString(initMode.getIm_username()),
                    UdeskUtil.objectToString(initMode.getIm_password())), mChatView.getEuid(), new HttpCallBack() {
                @Override
                public void onSuccess(String message) {
                    Merchant merchant = JsonUtils.parseMerchantDetail(message);
                    mChatView.setMerchant(merchant);
                }

                @Override
                public void onFail(Throwable message) {
                    if (message instanceof ConnectTimeoutException) {
                        if (UdeskLibConst.isDebug) {
                            Log.i("udesk", "getMerchantInfo result =" + mChatView.getContext().getString(R.string.time_out));
                        }
                    }
                }

                @Override
                public void onSuccessFail(String message) {
//                    {"code":"record_not_found","message":"没有找到商户"}
                    if (UdeskLibConst.isDebug) {
                        Log.i("udesk", "getMerchantInfo result =" + message);
                    }

                    try {
                        JSONObject object = new JSONObject(message);
                        String error = object.optString("message");
                        if (!TextUtils.isEmpty(error)) {
                            Toast.makeText(mChatView.getContext(), error, Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }


                }
            });
        }
    }


    //创建消息
    public void createMessage(final String id, final String messa, String type, ExtrasInfo info) {
        InitMode initMode = UdeskSDKManager.getInstance().getInitMode();
        if (initMode != null) {
            final SendMessage sendMessage = new SendMessage();
            sendMessage.setContent(messa);
            sendMessage.setContent_type(type);
            if (info != null) {
                sendMessage.setExtras(info);
            }
            HttpFacade.getInstance().createMessage(UdeskUtil.getAuthToken(UdeskUtil.objectToString(initMode.getIm_username()),
                    UdeskUtil.objectToString(initMode.getIm_password())), mChatView.getEuid(), sendMessage, new HttpCallBack() {
                @Override
                public void onSuccess(String backstirng) {
                    mChatView.checkConnect();
                    sendMessageResult(id, UdeskConst.SendFlag.RESULT_SUCCESS, JsonUtils.getCreateTime(backstirng));
                }

                @Override
                public void onFail(Throwable message) {
                    sendMessageResult(id, UdeskConst.SendFlag.RESULT_FAIL, new SendMsgResult());
                    if (message instanceof ConnectTimeoutException) {
                        if (UdeskLibConst.isDebug) {
                            Log.i("udesk", "createMessage result =" + mChatView.getContext().getString(R.string.time_out));
                        }
                    }
                }

                @Override
                public void onSuccessFail(String message) {
                    sendMessageResult(id, UdeskConst.SendFlag.RESULT_FAIL, new SendMsgResult());
                    if (UdeskLibConst.isDebug) {
                        Log.i("udesk", "createMessage result =" + message);
                    }
                }
            });
        }
    }

    /**
     * 获取消息列表
     */
    public void getMessages(final String fromUUID) {
        InitMode initMode = UdeskSDKManager.getInstance().getInitMode();
        if (initMode != null) {
            HttpFacade.getInstance().getMessages(UdeskUtil.getAuthToken(UdeskUtil.objectToString(initMode.getIm_username()),
                    UdeskUtil.objectToString(initMode.getIm_password())), mChatView.getEuid(), fromUUID, new HttpCallBack() {
                @Override
                public void onSuccess(String message) {
                    List<ReceiveMessage> messagess = JsonUtils.parserMessages(message);
                    Collections.reverse(messagess);
                    mChatView.addMessage(messagess, fromUUID);

                }

                @Override
                public void onFail(Throwable message) {
                    List<ReceiveMessage> messagess = new ArrayList<ReceiveMessage>();
                    mChatView.addMessage(messagess, fromUUID);
                    if (message instanceof ConnectTimeoutException) {
                        if (UdeskLibConst.isDebug) {
                            Log.i("udesk", "getMessages result =" + mChatView.getContext().getString(R.string.time_out));
                        }
                    }
                }

                @Override
                public void onSuccessFail(String message) {
                    if (UdeskLibConst.isDebug) {
                        Log.i("udesk", "getMessages result =" + message);
                    }
                    List<ReceiveMessage> messagess = new ArrayList<ReceiveMessage>();
                    mChatView.addMessage(messagess, fromUUID);
                }
            });
        }

    }

    public void setMessageRead() {
        InitMode initMode = UdeskSDKManager.getInstance().getInitMode();
        if (initMode != null) {
            HttpFacade.getInstance().setMessageRead(UdeskUtil.getAuthToken(UdeskUtil.objectToString(initMode.getIm_username()),
                    UdeskUtil.objectToString(initMode.getIm_password())), mChatView.getEuid(), new HttpCallBack() {
                @Override
                public void onSuccess(String message) {

                }

                @Override
                public void onFail(Throwable message) {

                }

                @Override
                public void onSuccessFail(String message) {

                }
            });
        }

    }


    /**
     * 消息发送结果
     *
     * @param msgId
     */
    public void sendMessageResult(String msgId, int status, SendMsgResult sendMsgResult) {
        try {
            sendMsgResult.setId(msgId);
            sendMsgResult.setFlag(status);
            if (mChatView != null && mChatView.getHandler() != null) {
                Message message = mChatView.getHandler().obtainMessage(
                        MessageWhat.changeImState);
                message.obj = sendMsgResult;
                mChatView.getHandler().sendMessage(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private ExecutorService scaleExecutor;

    private void ensureMessageExecutor() {
        if (scaleExecutor == null) {
            scaleExecutor = Concurrents
                    .newSingleThreadExecutor("scaleExecutor");
        }
    }

    public void scaleBitmap(final String path) {
        if (!TextUtils.isEmpty(path)) {
            ensureMessageExecutor();
            scaleExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Bitmap scaleImage = null;
                        byte[] data = null;
                        int max = 0;
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        /**
                         * 在不分配空间状态下计算出图片的大小
                         */
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(path, options);
                        int width = options.outWidth;
                        int height = options.outHeight;
                        max = Math.max(width, height);
                        options.inTempStorage = new byte[100 * 1024];
                        options.inJustDecodeBounds = false;
                        options.inPurgeable = true;
                        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        InputStream inStream = new FileInputStream(path);
                        data = readStream(inStream);
                        if (data == null || data.length <= 0) {
                            sendBitmapMessage(path);
                            return;
                        }
                        String imageName = UdeskUtil.MD5(data);
                        File scaleImageFile = UdeskUtil.getOutputMediaFile(mChatView.getContext(), imageName
                                + UdeskConst.ORIGINAL_SUFFIX);
                        if (!scaleImageFile.exists()) {
                            // 缩略图不存在，生成上传图
                            if (max > UdeskConfig.ScaleMax) {
                                options.inSampleSize = max / UdeskConfig.ScaleMax;
                            } else {
                                options.inSampleSize = 1;
                            }
                            FileOutputStream fos = new FileOutputStream(scaleImageFile);
                            scaleImage = BitmapFactory.decodeByteArray(data, 0,
                                    data.length, options);
                            scaleImage.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                            fos.close();
                            fos = null;
                        }

                        if (scaleImage != null) {
                            scaleImage.recycle();
                            scaleImage = null;
                        }
                        data = null;
                        if (TextUtils.isEmpty(scaleImageFile.getPath())) {
                            sendBitmapMessage(path);
                        } else {
                            sendBitmapMessage(scaleImageFile.getPath());
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    } catch (OutOfMemoryError error) {
                        error.printStackTrace();
                    }
                }
            });
        }
    }


    /**
     * @param inStream
     * @return byte[]
     * @throws Exception
     */
    public byte[] readStream(InputStream inStream) throws Exception {
        byte[] buffer = new byte[1024];
        int len = -1;
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        while ((len = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, len);
        }
        byte[] data = outStream.toByteArray();
        outStream.close();
        inStream.close();
        return data;

    }


}
