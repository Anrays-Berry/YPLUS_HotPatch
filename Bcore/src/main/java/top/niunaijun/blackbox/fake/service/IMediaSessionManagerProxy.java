package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Context;

import java.lang.reflect.Method;

import black.android.media.session.BRISessionManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IMediaSessionManagerProxy extends BinderInvocationStub {

    public IMediaSessionManagerProxy() {
        super(BRServiceManager.get().getService(Context.MEDIA_SESSION_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRISessionManagerStub.get().asInterface(BRServiceManager.get().getService(Context.MEDIA_SESSION_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("createSession")
    public static class CreateSession extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String) {
                args[0] = BlackBoxCore.getHostPkg();
            }
            return method.invoke(who, args);
        }
    }

    /**
     * 沙箱业务取"当前活跃媒体会话"（歌词/歌名/进度全靠它）。
     *
     * 系统只认真实授权列表里的组件，业务传进来的是自己的虚拟组件 → 必被拒绝
     * （SecurityException）。这里换成宿主已授权的监听组件，
     * 于是"宿主开了通知使用权"就等于"盒子里的 app 有了"，业务侧逻辑完全不用改。
     *
     * 🔴 方法名必须是 `getSessions`（ISessionManager 的 AIDL 方法名）：
     *    `getActiveSessions` 是 framework 层 MediaSessionManager 的方法，Binder 层没有它，
     *    写错会导致 hook 永远匹配不到（沙箱里拿不到任何活跃会话，表现为播放状态恒为 false）。
     */
    @ProxyMethod("getSessions")
    public static class GetActiveSessions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof ComponentName) {
                        args[i] = HostNotifyCompat.listenerComponent();
                    }
                }
            }
            return method.invoke(who, args);
        }
    }
}
