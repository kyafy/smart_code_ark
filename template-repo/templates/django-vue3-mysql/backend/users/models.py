from django.db import models


class UserRecord(models.Model):
    name = models.CharField(max_length=64)
    email = models.EmailField(unique=True, max_length=128)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]
