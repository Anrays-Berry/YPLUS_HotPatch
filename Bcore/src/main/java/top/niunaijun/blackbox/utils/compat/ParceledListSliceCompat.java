package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;
import java.util.List;

import black.android.content.pm.BRParceledListSlice;

public class ParceledListSliceCompat {

	public static boolean isReturnParceledListSlice(Method method) {
		return method != null && method.getReturnType() == BRParceledListSlice.getRealClass();
	}

	public static boolean isParceledListSlice(Object obj) {
		return obj != null && obj.getClass() == BRParceledListSlice.getRealClass();
	}

	public static Object create(List<?> list) {
		Object slice = BRParceledListSlice.get()._new(list);
		if (slice != null) {
			return slice;
		} else {
			slice = BRParceledListSlice.get()._new();
		}
		for (Object item : list) {
			BRParceledListSlice.get(slice).append(item);
		}
		BRParceledListSlice.get(slice).setLastSlice(true);
		return slice;
	}

	/**
	 * 从 ParceledListSlice（或普通 List）里取出元素列表。
	 *
	 * 合并「沙箱内包 + 宿主真实包」时，需要解析原始系统 PM 的返回值：
	 * Android N+ 返回 ParceledListSlice，旧版本直接返回 List。
	 * 这里用普通反射调 getList()，不依赖生成的 BR 类是否声明了该方法。
	 */
	@SuppressWarnings("unchecked")
	public static <T> List<T> getList(Object slice) {
		if (slice == null) {
			return null;
		}
		if (slice instanceof List) {
			return (List<T>) slice;
		}
		try {
			Method method = slice.getClass().getMethod("getList");
			Object list = method.invoke(slice);
			if (list instanceof List) {
				return (List<T>) list;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}
}
