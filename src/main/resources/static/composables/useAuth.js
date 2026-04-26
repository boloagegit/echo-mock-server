/**
 * useAuth - 認證與權限管理 Composable
 *
 * 管理使用者登入狀態、管理員權限，以及登入/登出跳轉。
 * `requireLogin` 在未登入時彈出確認對話框，引導使用者前往登入頁面。
 *
 * @param {Function} showConfirm - 確認對話框函式（來自 useToast）
 * @param {Function} t - 翻譯函式（來自 useI18n）
 * @returns {{ isAdmin: Ref<boolean>, isLoggedIn: Ref<boolean>, login: Function, logout: Function, requireLogin: Function }}
 */
const useAuth = (showConfirm, t) => {
    const { ref } = Vue;

    const isAdmin = ref(false);
    const isLoggedIn = ref(false);

    /** 跳轉至登入頁面 */
    const login = () => { window.location.href = '/login.html'; };

    /** 登出並跳轉 */
    const logout = () => { window.location.href = '/api/auth/logout'; };

    /**
     * 檢查是否已登入，未登入時彈出確認對話框
     * @returns {Promise<boolean>} 已登入回傳 true，未登入且取消回傳 false
     */
    const requireLogin = async () => {
        if (!isLoggedIn.value) {
            const go = await showConfirm({ title: t('confirm.requireLogin'), message: t('confirm.requireLoginMsg'), confirmText: t('confirm.goToLogin') });
            if (go) { login(); }
            return false;
        }
        return true;
    };

    return { isAdmin, isLoggedIn, login, logout, requireLogin };
};
