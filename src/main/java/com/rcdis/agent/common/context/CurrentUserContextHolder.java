package com.rcdis.agent.common.context;

public final class CurrentUserContextHolder {

    private static final ThreadLocal<CurrentUserTO> CURRENT_USER = new ThreadLocal<>();

    private CurrentUserContextHolder() {
        throw new UnsupportedOperationException("CurrentUserContextHolder cannot be instantiated");
    }

    public static void set(CurrentUserTO currentUser) {
        CURRENT_USER.set(currentUser);
    }

    public static CurrentUserTO currentOrAnonymous() {
        CurrentUserTO currentUser = CURRENT_USER.get();
        if (currentUser == null) {
            return CurrentUserTO.anonymous();
        }
        return currentUser;
    }

    public static CurrentUserTO currentOrNull() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
