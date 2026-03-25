from django.urls import path

from .views import health_view, users_view

urlpatterns = [
    path("health", health_view),
    path("users", users_view),
]
