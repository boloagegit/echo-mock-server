/**
 * useTour - Interactive Tour Composable
 *
 * Manage the status and step logic of novice guide tour.
 * Select Start Navigation from the Help File → Open the Demonstration Rules → Return to the pre-entry situation after the end.
 *
 * @param {Object} deps - { t, onFinish }
 * @returns {{ tourActive, tourStep, helpSeen, startTour, nextStep, prevStep, skipTour, tourSteps }}
 */
const useTour = ({ t, onFinish = () => {} }) => {
    const { ref, computed } = Vue;

    const helpSeen = ref(localStorage.getItem('echo_help_seen') === '1');
    const tourActive = ref(false);
    const tourStep = ref(0);

    const tourSteps = computed(() => [
        {
            target: '[data-tour="protocol"]',
            title: t('tour.stepProtocolTitle'),
            body: t('tour.stepProtocolBody'),
        },
        {
            target: '[data-tour="match"]',
            title: t('tour.stepMatchTitle'),
            body: t('tour.stepMatchBody'),
        },
        {
            target: '[data-tour="conditions"]',
            title: t('tour.stepConditionTitle'),
            body: t('tour.stepConditionBody'),
        },
        {
            target: '[data-tour="response"]',
            title: t('tour.stepResponseTitle'),
            body: t('tour.stepResponseBody'),
        },
        {
            target: '[data-tour="save"]',
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
        if (!tourActive.value) return;
        tourActive.value = false;
        tourStep.value = 0;
        helpSeen.value = true;
        localStorage.setItem('echo_help_seen', '1');
        onFinish();
    };

    return { tourActive, tourStep, helpSeen, startTour, nextStep, prevStep, skipTour, tourSteps };
};
