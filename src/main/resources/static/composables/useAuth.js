/**
 * useAuth - Authentication and authority management Composable
 *
 * Manage user login status, administrator permissions, and login/logout jumps.
 * `requireLogin` Pops up a confirmation dialog box when not logged in, guiding the user to the login page.
 *
 * @param {Function} showConfirm - Confirm dialog function (from useToast)
 * @param {Function} t - Translation function (from useI18n)
 * @returns {{ isAdmin: Ref<boolean>, isLoggedIn: Ref<boolean>, login: Function, logout: Function, requireLogin: Function }}
 */
const useAuth = (showConfirm, t) => {
    const { ref } = Vue;

    const isAdmin = ref(false);
    const isLoggedIn = ref(false);

    /** ADMIN-only, DB-independent diagnostic entry. Never trusts locally stored roles. */
    const verifyAdminResourceAccess = async () => {
        const controller = new AbortController();
        const deadline = setTimeout(() => controller.abort(), 8000);
        try {
            const response = await fetch('/api/admin/resources/access', { cache: 'no-store', signal: controller.signal });
            isAdmin.value = response.ok;
            if (response.ok) isLoggedIn.value = true;
            else if (response.status === 401) isLoggedIn.value = false;
        } catch {
            isAdmin.value = false;
        } finally { clearTimeout(deadline); }
    };

    /** Jump to login page */
    const login = () => { window.location.href = '/login.html'; };

    /** Log out and jump */
    const logout = () => {
        // Drop the cached identity and page snapshot so the next load cannot briefly show signed-in content.
        try {
            sessionStorage.removeItem('echo.statusCache');
            sessionStorage.removeItem('echo.pageSnapshot');
        } catch { /* storage unavailable */ }
        window.location.href = '/api/auth/logout';
    };

    /**
     * Check whether you are logged in, and a confirmation dialog box will pop up if you are not logged in.
     * @returns {Promise<boolean>} true if logged in; false if login was required and cancelled.
     */
    const requireLogin = async () => {
        if (!isLoggedIn.value) {
            const go = await showConfirm({ title: t('confirm.requireLogin'), message: t('confirm.requireLoginMsg'), confirmText: t('confirm.goToLogin') });
            if (go) { login(); }
            return false;
        }
        return true;
    };

    return { isAdmin, isLoggedIn, login, logout, requireLogin, verifyAdminResourceAccess };
};
