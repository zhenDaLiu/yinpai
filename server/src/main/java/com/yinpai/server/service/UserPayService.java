package com.yinpai.server.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeAppPayModel;
import com.alipay.api.request.AlipayTradeAppPayRequest;
import com.alipay.api.response.AlipayTradeAppPayResponse;
import com.github.binarywang.wxpay.bean.order.WxPayAppOrderResult;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderRequest;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.constant.WxPayConstants;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.google.gson.Gson;
import com.yinpai.server.config.AlipayConfig;
import com.yinpai.server.config.WechatConfig;
import com.yinpai.server.config.WechatJsApiConfig;
import com.yinpai.server.domain.dto.LoginUserInfoDto;
import com.yinpai.server.domain.entity.*;
import com.yinpai.server.domain.entity.admin.Admin;
import com.yinpai.server.domain.repository.UserOrderRepository;
import com.yinpai.server.domain.repository.UserPayRepository;
import com.yinpai.server.domain.repository.admin.AdminRepository;
import com.yinpai.server.enums.PayStatus;
import com.yinpai.server.exception.NotAcceptableException;
import com.yinpai.server.exception.NotLoginException;
import com.yinpai.server.exception.ProjectException;
import com.yinpai.server.thread.threadlocal.LoginUserThreadLocal;
import com.yinpai.server.utils.*;
import com.yinpai.server.vo.AdminPayMethodVo;
import com.yinpai.server.vo.admin.AdminAddForm;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.yinpai.server.service.WXJSPayService.*;
import static java.util.Calendar.MINUTE;
import static java.util.Calendar.getInstance;

/**
 * @author weilai
 * @email 352342845@qq.com
 * @date 2020/9/29 8:54 下午
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class UserPayService {

    private final UserPayRepository userPayRepository;

    private final UserService userService;

    private final WorksService worksService;

    private final AdminService adminService;

    private final WechatConfig wechatConfig;

    private final AlipayConfig alipayConfig;

    private final UserPayRecordService userPayRecordService;

    private final AdminRepository adminRepository;

    @Autowired
    private WechatJsApiConfig wechatJsApiConfig;

    @Autowired
    public UserPayService(UserPayRepository userPayRepository, UserService userService, WorksService worksService, AdminService adminService, WechatConfig wechatConfig, AlipayConfig alipayConfig, @Lazy UserPayRecordService userPayRecordService, AdminRepository adminRepository) {
        this.userPayRepository = userPayRepository;
        this.userService = userService;
        this.worksService = worksService;
        this.adminService = adminService;
        this.wechatConfig = wechatConfig;
        this.alipayConfig = alipayConfig;
        this.userPayRecordService = userPayRecordService;
        this.adminRepository = adminRepository;
    }

    public boolean isPayWork(Integer workId, Integer adminId, Integer userId) {
        UserPay workPay = userPayRepository.findByEntityIdAndUserIdAndType(workId, userId, 1);
        if (workPay != null) {
            return true;
        }
        return isPayAdmin(adminId, userId);
    }

    public boolean isPayAdmin(Integer adminId, Integer userId) {
        UserPay adminPay = userPayRepository.findByEntityIdAndUserIdAndType(adminId, userId, 2);
        if (adminPay == null) {
            return false;
        }
        return new Date().compareTo(adminPay.getExpireTime()) < 0;
    }

    public void userPayWork(Integer workId) {
        LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
        if (userInfoDto == null) {
            throw new NotLoginException("请先登陆");
        }
        Works works = worksService.findByIdNotNull(workId);
        if (isPayWork(workId, works.getAdminId(), userInfoDto.getUserId())) {
            throw new NotAcceptableException("已经购买过了");
        }
        synchronized (this) {
            User user = userService.findByIdNotNull(userInfoDto.getUserId());
            int balance = user.getMoney() - works.getPrice();
            if (balance < 0) {
                throw new NotAcceptableException("余额不足");
            }
            user.setMoney(balance);
            userService.save(user);
        }
        UserPay userPay = new UserPay();
        userPay.setUserId(userInfoDto.getUserId());
        userPay.setEntityId(workId);
        userPay.setType(1);
        userPay.setCreateTime(new Date());
        userPayRepository.save(userPay);
        UserPayRecord userPayRecord = new UserPayRecord();
        userPayRecord.setUserId(userInfoDto.getUserId());
        userPayRecord.setAdminId(works.getAdminId());
        userPayRecord.setType(works.getType());
        userPayRecord.setMoney(works.getPrice());
        userPayRecord.setCreateTime(new Date());
        userPayRecordService.save(userPayRecord);
        Admin admin = adminService.findByIdNotNull(works.getAdminId());
        admin.setMoney(admin.getMoney() + works.getPrice());
        adminRepository.save(admin);
    }

    public void userPayAdmin(Integer adminId, String type, Integer amount) {
        LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
        if (userInfoDto == null) {
            throw new NotLoginException("请先登陆");
        }
        Admin admin = adminService.findByIdNotNull(adminId);
        int price;
        Date expireTime;
        UserPay userPay = userPayRepository.findByEntityIdAndUserIdAndType(adminId, userInfoDto.getUserId(), 2);
        if (userPay == null) {
            expireTime = new Date();
        } else {
            expireTime = userPay.getExpireTime();
        }
        if ("month".equalsIgnoreCase(type)) {
            if (admin.getMonthPayPrice() <= 0) {
                throw new NotAcceptableException("该作者暂未开通月付方式");
            }
            price = admin.getMonthPayPrice() * amount;
            expireTime = DateUtil.timeAdd(expireTime, Calendar.MONTH, amount);
        } else if ("quarter".equalsIgnoreCase(type)) {
            if (admin.getQuarterPayPrice() <= 0) {
                throw new NotAcceptableException("该作者暂未开通季付方式");
            }
            price = admin.getQuarterPayPrice() * amount;
            expireTime = DateUtil.timeAdd(expireTime, Calendar.MONTH, amount * 3);
        } else if ("year".equalsIgnoreCase(type)) {
            if (admin.getYearPayPrice() <= 0) {
                throw new NotAcceptableException("该作者暂未开通年付方式");
            }
            price = admin.getYearPayPrice() * amount;
            expireTime = DateUtil.timeAdd(expireTime, Calendar.YEAR, amount);
        } else {
            throw new NotAcceptableException("类型错误");
        }
        synchronized (this) {
            User user = userService.findByIdNotNull(userInfoDto.getUserId());
            int balance = user.getMoney() - price;
            if (balance < 0) {
                throw new NotAcceptableException("余额不足");
            }
            user.setMoney(balance);
            userService.save(user);
        }
        if (userPay == null) {
            userPay = new UserPay();
            userPay.setUserId(userInfoDto.getUserId());
            userPay.setEntityId(adminId);
            userPay.setType(2);
            userPay.setCreateTime(new Date());
        }
        userPay.setExpireTime(expireTime);
        userPayRepository.save(userPay);
        UserPayRecord userPayRecord = new UserPayRecord();
        userPayRecord.setUserId(userInfoDto.getUserId());
        userPayRecord.setAdminId(adminId);
        userPayRecord.setType(3);
        userPayRecord.setMoney(price);
        userPayRecord.setCreateTime(new Date());
        userPayRecordService.save(userPayRecord);
        admin.setMoney(admin.getMoney() + price);
        adminRepository.save(admin);
    }

    public AdminPayMethodVo adminPayMethod(Integer adminId) {
        LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
        if (userInfoDto == null) {
            throw new NotLoginException("请先登陆");
        }
        Admin admin = adminService.findByIdNotNull(adminId);
        AdminPayMethodVo vo = new AdminPayMethodVo();
        vo.setMonthPayPrice(admin.getMonthPayPrice());
        vo.setQuarterPayPrice(admin.getQuarterPayPrice());
        vo.setYearPayPrice(admin.getYearPayPrice());
        UserPay userPay = userPayRepository.findByEntityIdAndUserIdAndType(adminId, userInfoDto.getUserId(), 2);
        if (userPay != null) {
            vo.setExpireTime(userPay.getExpireTime());
            int surplus = 0;
            if (null != userPay.getExpireTime()) {
                surplus = DateUtil.getDayDiff(new Date(), userPay.getExpireTime());
                if (surplus <= 0) {
                    surplus = 0;
                }
                if (surplus == 0) {
                    if (DateUtil.isNow(userPay.getExpireTime())) {
                        // 时间是当天的话 加 1
                        surplus = surplus + 1;
                    }
                }
            }
            vo.setSurplusDay(surplus);
        }
        return vo;
    }

    @Autowired
    private UserOrderRepository userOrderRepository;

    private UserOrder save(UserOrder userOrder) {
        return userOrderRepository.save(userOrder);
    }


    public WxPayAppOrderResult wechatPayMoney(String amount) {
        //获取用户信息
        LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
        if (userInfoDto == null) {
            throw new NotLoginException("请先登陆");
        }
        WxPayService wxPayService = new WxPayServiceImpl();
        WxPayConfig wxPayConfig = wechatConfig.appPayConfig();
        //交易,统一APP
        wxPayConfig.setTradeType("APP");
        wxPayService.setConfig(wxPayConfig);
        //WxPayUnifiedOrderRequest:商品信息
        WxPayUnifiedOrderRequest orderRequest = new WxPayUnifiedOrderRequest();
        //商品描述
        orderRequest.setBody("收集宝");
        Long orderId = new IdWorker(1, 1, 1).nextId();
        //商户订单号
        orderRequest.setOutTradeNo(orderId + "");
        BigDecimal price = new BigDecimal(amount).multiply(new BigDecimal(100));
        //价钱
        orderRequest.setTotalFee(Integer.parseInt(price + ""));
        //ip地址
        orderRequest.setSpbillCreateIp(ProjectUtil.getIpAddr());
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        Calendar expire = getInstance();
        expire.add(MINUTE, 10);
        //交易开始时间
        orderRequest.setTimeStart(timeFormat.format(new Date()));
        //交易结束时间
        orderRequest.setTimeExpire(timeFormat.format(expire.getTime()));
        try {
            WxPayAppOrderResult wxPayAppOrderResult = wxPayService.createOrder(orderRequest);
            //todo 生成订单信息
            UserOrder userOrder = UserOrder.builder()
                    .orderId(orderId)
                    .userId(userInfoDto.getUserId() + "")
                    .body("收集宝")
                    .totalFee(price) // todo 测试时使用
                    .ipAddress(ProjectUtil.getIpAddr())
                    .timeStart(DateUtil.getMMDDYYHHMMSS(new Date()))
                    .timeExpire(DateUtil.getMMDDYYHHMMSS(expire.getTime()))
                    .payPlatform("WeChatPay")
                    .orderPayStatus(0)
                    .orderShipStatus(0)
                    .orderStatus(PayStatus.unpaid)
                    .timeStamp(wxPayAppOrderResult.getTimeStamp())
                    .orderMetaData("{\"sign\":\"" + wxPayAppOrderResult.getSign() + "\"}").build();
            save(userOrder);
            //todo 二次加密
            return wxPayAppOrderResult;
        } catch (WxPayException e) {
            // TODO
            log.error("【唤醒微信APP支付失败】订单ID：{}, 信息：{}", orderId, e.getMessage(), e);
            throw new ProjectException("微信统一下单失败");
        }
    }


    //公众号支付
    public String jsapiPayMoney(String amount) {
        //获取用户信息
        LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
        if (userInfoDto == null) {
            throw new NotLoginException("请先登陆");
        }
        WxPayService wxPayService = new WxPayServiceImpl();
        WxPayConfig wxPayConfig = wechatConfig.appPayConfig();
        //交易,统一APP
        wxPayConfig.setTradeType("APP");
        wxPayService.setConfig(wxPayConfig);
        //WxPayUnifiedOrderRequest:商品信息
        WxPayUnifiedOrderRequest orderRequest = new WxPayUnifiedOrderRequest();
        //商品描述
        orderRequest.setBody("收集宝");
        Long orderId = new IdWorker(1, 1, 1).nextId();
        //商户订单号
        orderRequest.setOutTradeNo(orderId + "");
        //todo 上线使用price
        BigDecimal price = new BigDecimal(amount).multiply(new BigDecimal(100));
        //价钱
        orderRequest.setTotalFee(Integer.parseInt(price + ""));
        //ip地址
        orderRequest.setSpbillCreateIp(ProjectUtil.getIpAddr());
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        Calendar expire = getInstance();
        expire.add(MINUTE, 10);
        //交易开始时间
        orderRequest.setTimeStart(timeFormat.format(new Date()));
        //交易结束时间
        orderRequest.setTimeExpire(timeFormat.format(expire.getTime()));
        try {
            WxPayAppOrderResult wxPayAppOrderResult = wxPayService.createOrder(orderRequest);
            //todo 生成订单信息
            UserOrder userOrder = UserOrder.builder()
                    .orderId(orderId)
                    .userId(userInfoDto.getUserId() + "")
                    .body("收集宝")
                    .totalFee(price) // todo 测试时使用
                    .ipAddress(ProjectUtil.getIpAddr())
                    .timeStart(DateUtil.getMMDDYYHHMMSS(new Date()))
                    .timeExpire(DateUtil.getMMDDYYHHMMSS(expire.getTime()))
                    .payPlatform("WeChatJsApiPay")
                    .orderPayStatus(0)
                    .orderShipStatus(0)
                    .orderStatus(PayStatus.unpaid)
                    .timeStamp(wxPayAppOrderResult.getTimeStamp())
                    .orderMetaData("{\"sign\":\"" + wxPayAppOrderResult.getSign() + "\"}").build();
            save(userOrder);
            //todo 公众号二次加密
            //return wxPayAppOrderResult;
        } catch (WxPayException e) {
            // TODO
            log.error("【唤醒微信APP支付失败】订单ID：{}, 信息：{}", orderId, e.getMessage(), e);
            throw new ProjectException("微信统一下单失败");
        }
        return "";
    }


    public String aliPayMoney(String amount) {
        try {
            //获取用户信息
            LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
            if (userInfoDto == null) {
                throw new NotLoginException("请先登陆");
            }
            AlipayClient alipayClient = new DefaultAlipayClient(
                    alipayConfig.getServerUrl(),
                    alipayConfig.getAppId(),    //app_id
                    alipayConfig.getAppPrivateKey(),
                    "json",         //format
                    alipayConfig.getCharset(),    //charset
                    alipayConfig.getAlipayPublicKey(),
                    alipayConfig.getSignType());    //sign_type
            //实例化具体API对应的request类,类名称和接口名称对应,当前调用接口名称：alipay.trade.app.pay
            AlipayTradeAppPayRequest request = new AlipayTradeAppPayRequest();
            //SDK已经封装掉了公共参数，这里只需要传入业务参数。以下方法为sdk的model入参方式(model和biz_content同时存在的情况下取biz_content)。
            AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
            model.setBody("收集宝");
            model.setSubject("购买商品");
            IdWorker idWorker = new IdWorker(1, 1, 1);
            long orderId = idWorker.nextId();
            model.setOutTradeNo(orderId + "");
            model.setTimeoutExpress(alipayConfig.getTimeoutExpress());
            // TODO 订单总金额,暂时用1 ,生产使用 amount
            model.setTotalAmount("0.01");
            model.setProductCode(orderId + "");
            //创建订单
            {
                Calendar expire = getInstance();
                expire.add(MINUTE, 10);
                UserOrder userOrder = UserOrder.builder()
                        .orderId(orderId)
                        .userId(userInfoDto.getUserId() + "")
                        .body("收集宝")
                        // TODO: 2020/12/14 支付宝订单金额单位是元  订单内是分  (需要*100转换)
                        .totalFee(new BigDecimal(1))
                        .ipAddress(ProjectUtil.getIpAddr())
                        .payPlatform("AliPay")
                        .timeStart(DateUtil.getMMDDYYHHMMSS(new Date()))
                        .timeExpire(DateUtil.getMMDDYYHHMMSS(expire.getTime()))
                        .timeStamp(System.currentTimeMillis() + "")
                        .orderPayStatus(0)
                        .orderShipStatus(0)
                        .orderStatus(PayStatus.unpaid)
                        .build();
                userOrderRepository.save(userOrder);
            }

            /*
             * 1.电脑网站支付产品alipay.trade.page.pay接口中，product_code为：FAST_INSTANT_TRADE_PAY
             * 2.手机网站支付产品alipay.trade.wap.pay接口中，product_code为：QUICK_WAP_WAY
             * 3.当面付条码支付产品alipay.trade.pay接口中，product_code为：FACE_TO_FACE_PAYMENT
             * 4.APP支付产品alipay.trade.app.pay接口中，product_code为：QUICK_MSECURITY_PAY
             */
            model.setProductCode("QUICK_MSECURITY_PAY");
            request.setBizModel(model);
            //支付宝异步回调接口
            request.setNotifyUrl(alipayConfig.getNotifyUrl());
            //这里和普通的接口调用不同，使用的是sdkExecute
            AlipayTradeAppPayResponse response = alipayClient.sdkExecute(request);
            //记录支付记录,同微信一致
            return response.getBody();
        } catch (AlipayApiException e) {
            log.error("【唤醒支付宝APP支付失败】订单ID：{}, 信息", e.getMessage(), e);
            throw new ProjectException("支付宝统一下单失败");
        }
    }

    @Autowired
    private RestTemplate restTemplate;

    public Map<String, Object> wechatAuth(String code, Integer totalFee) throws IOException {
        LoginUserInfoDto userInfoDto = LoginUserThreadLocal.get();
        if (userInfoDto == null) {
            throw new NotLoginException("请先登陆");
        }
        String ipAddr = ProjectUtil.getIpAddr();
        log.info("【支付用户IP地址】: {}", ipAddr);
        String format = MessageFormat.format("https://api.weixin.qq.com/sns/oauth2/access_token?appid={0}&secret={1}&code={2}&grant_type=authorization_code",
                "wx96095d1c2acffb94", "83888971018c3054bd1ad72f099edea8", code);
        String s = restTemplate.getForObject(format, String.class);
        log.info("【openid 返回结果 】: {}", new Gson().toJson(s));
        Map<String, String> codeMap = JsonUtils.toObject(s, Map.class);
        IdWorker idWorker = new IdWorker(1, 1, 1);
        long orderId = idWorker.nextId();
        String body = "公众号支付";            // 商家自己随便写的消息
        String out_trade_no = String.valueOf(orderId);  // 订单ID
        totalFee = totalFee * 100; // 金额转成分  // 金额
        String userIp = "111.204.59.194"; // 用户IP地址
        //String userIp = getIpAddr(request); // 用户IP地址
        String openId = codeMap.get("openid");  // code 请求返回的ID
        String s1 = unifiedOrder(body, out_trade_no, totalFee, ipAddr, openId); // 获取预支付结果
        log.info("【预支付返回结果 】: {}", new Gson().toJson(s1));
        Map<String, Object> mapMap = getPayMap(s1);
        //todo 生成订单信息
        UserOrder userOrder = UserOrder.builder()
                .orderId(orderId)
                .userId(userInfoDto.getUserId() + "")
                .body("收集宝")
                .totalFee(new BigDecimal(totalFee)) // todo 测试时使用
                .ipAddress(ProjectUtil.getIpAddr())
                .timeStart(DateUtil.getMMDDYYHHMMSS(new Date()))
                .timeExpire(DateUtil.getMMDDYYHHMMSS(new Date()))
                .payPlatform("JSAPIPay")
                .orderPayStatus(0)
                .orderShipStatus(0)
                .orderStatus(PayStatus.unpaid)
                .timeStamp(DateUtil.getMMDDYYHHMMSS(new Date()))
                .orderMetaData("{\"sign\":\"" + mapMap.get("paySign") + "\"}").build();
        save(userOrder);
        return mapMap;// 解析xml 返回 map
    }

}
