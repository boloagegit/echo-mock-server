/**
 * useTour - 互動式導覽 Composable
 *
 * 管理新手引導 tour 的狀態與步驟邏輯。
 * 點擊 sidebar 說明（首次）→ 開啟新增規則 Modal → 逐步高亮 Modal 內元素。
 *
 * @param {Object} deps - { t, openCreate }
 * @returns {{ tourActive, tourStep, helpSeen, startTour, nextStep, prevStep, skipTour, tourSteps }}
 */
const useTour = ({ t }) => {
    const { ref, computed } = Vue;

    const helpSeen = ref(localStorage.getItem('echo_help_seen') === '1');
    const tourActive = ref(false);
    const tourStep = ref(0);

    const tourSteps = computed(() => [
        {
            target: '.protocol-switch',
            title: t('tour.stepProtocolTitle'),
            body: t('tour.stepProtocolBody'),
        },
        {
            target: '.rule-left .form-block:nth-child(2)',
            title: t('tour.stepMatchTitle'),
            body: t('tour.stepMatchBody'),
        },
        {
            target: '.rule-left .form-block:nth-child(3)',
            title: t('tour.stepConditionTitle'),
            body: t('tour.stepConditionBody'),
        },
        {
            target: '.rule-right .form-block:first-child',
            title: t('tour.stepResponseTitle'),
            body: t('tour.stepResponseBody'),
        },
        {
            target: '.modal-footer',
            title: t('tour.stepSaveTitle'),
            body: t('tour.stepSaveBody'),
        },
    ]);

    const startTour = () => {
        tourStep.value = 0;
        tourActive.value = true;
    };

    const nextStep = () => {
        if (tourStep.value < tourSteps.value.length - 1) {
            tourStep.value++;
        } else {
            finishTour();
        }
    };

    const prevStep = () => {
        if (tourStep.value > 0) { tourStep.value--; }
    };

    const skipTour = () => {
        finishTour();
    };

    const finishTour = () => {
        tourActive.value = false;
        tourStep.value = 0;
        helpSeen.value = true;
        localStorage.setItem('echo_help_seen', '1');
    };

    return { tourActive, tourStep, helpSeen, startTour, nextStep, prevStep, skipTour, tourSteps };
};
