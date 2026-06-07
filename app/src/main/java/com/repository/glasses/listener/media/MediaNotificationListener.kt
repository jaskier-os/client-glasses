package com.repository.glasses.listener.media

import android.service.notification.NotificationListenerService

/**
 * Empty NotificationListenerService -- exists solely as a permission gate
 * for MediaSessionManager.getActiveSessions(). Does not process notifications.
 */
class MediaNotificationListener : NotificationListenerService()
