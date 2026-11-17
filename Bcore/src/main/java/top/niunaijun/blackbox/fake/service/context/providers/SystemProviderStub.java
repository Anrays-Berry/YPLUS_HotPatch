package top.niunaijun.blackbox.fake.service.context.providers;

import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;


public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        
        String methodName = method.getName();
        
        
        
        if ("call".equals(methodName)) {
            
            if (args != null) {
                Class<?> attributionSourceClass = BRAttributionSource.getRealClass();
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    
                    if (arg != null && attributionSourceClass != null && 
                            arg.getClass().getName().equals(attributionSourceClass.getName())) {
                        ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                    }
                }
            }
            Object result = method.invoke(mBase, args);
            // 通知使用权透传：业务零改动也能读到"自己的监听组件已启用"
            return rewriteNotificationListeners(args, result);
        }
        
        
        if (args != null && args.length > 0) {
            Object arg = args[0];
            if (arg instanceof String) {
                String authority = (String) arg;
                
                if (!isSystemProviderAuthority(authority)) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
            } else if (arg != null) {
                Class<?> attrSourceClass = BRAttributionSource.getRealClass();
                
                if (attrSourceClass != null && arg.getClass().getName().equals(attrSourceClass.getName())) {
                    ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                }
            }
        }
        return method.invoke(mBase, args);
    }

    /**
     * 通知使用权透传（用户 2026-10-28）：
     * 沙箱里真正的通知使用权在**宿主包**上，而虚拟应用（如业务应用）检查的是**自己的虚拟组件名**，
     * 导致"框架有权限、业务看不到"。读取 `enabled_notification_listeners` 时，若宿主已启用，
     * 就把该虚拟应用自己声明的 NotificationListenerService 组件也拼进返回值 —— 业务侧无需任何配合。
     * 只认 GET_secure/GET_global/GET_system + 该 key；其它设置一概不碰；任何异常都返回原值。
     */
    private Object rewriteNotificationListeners(Object[] args, Object result) {
        try {
            if (!(result instanceof Bundle) || args == null) return result;
            boolean isGet = false, isKey = false;
            for (Object a : args) {
                if (a instanceof String) {
                    String s = (String) a;
                    if ("GET_secure".equals(s) || "GET_global".equals(s) || "GET_system".equals(s)) isGet = true;
                    else if ("enabled_notification_listeners".equals(s)) isKey = true;
                }
            }
            if (!isGet || !isKey) return result;

            Bundle bundle = (Bundle) result;
            String value = bundle.getString("value");
            if (value == null || value.isEmpty()) return result;
            // 宿主没启用通知使用权 → 原样返回（诊断仍能如实判为"未授权"）
            if (!value.contains(BlackBoxCore.getHostPkg() + "/")) return result;

            List<String> components = virtualListenerComponents();
            if (components.isEmpty()) return result;
            StringBuilder sb = new StringBuilder(value);
            for (String comp : components) {
                if (!value.contains(comp)) sb.append(':').append(comp);
            }
            bundle.putString("value", sb.toString());
            return bundle;
        } catch (Throwable t) {
            return result;
        }
    }

    /** 当前虚拟应用自己声明的 NotificationListenerService 组件（从虚拟 PM 查，不硬编码类名） */
    private List<String> virtualListenerComponents() {
        List<String> out = new ArrayList<>();
        try {
            String pkg = BActivityThread.getAppPackageName();
            if (pkg == null || pkg.isEmpty()) return out;
            int userId = BActivityThread.getUserId();
            PackageInfo pi = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_SERVICES, userId);
            if (pi == null || pi.services == null) return out;
            for (ServiceInfo si : pi.services) {
                if (si == null || si.name == null) continue;
                if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(si.permission)) {
                    out.add(new ComponentName(pkg, si.name).flattenToString());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null) return false;
        
        return authority.equals("settings") || 
               authority.equals("media") || 
               authority.equals("downloads") || 
               authority.equals("contacts") || 
               authority.equals("call_log") || 
               authority.equals("telephony") || 
               authority.equals("calendar") || 
               authority.equals("browser") || 
               authority.equals("user_dictionary") || 
               authority.equals("applications") ||
               authority.startsWith("com.android.") ||
               authority.startsWith("android.");
    }
}
