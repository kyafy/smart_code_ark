import json

from django.conf import settings
from django.db import IntegrityError
from django.http import HttpRequest, JsonResponse
from django.views.decorators.csrf import csrf_exempt

from .models import UserRecord


def health_view(_: HttpRequest) -> JsonResponse:
    return JsonResponse(
        {
            "status": "UP",
            "service": settings.APP_NAME,
        }
    )


@csrf_exempt
def users_view(request: HttpRequest) -> JsonResponse:
    if request.method == "GET":
        payload = [
            {
                "id": user.id,
                "name": user.name,
                "email": user.email,
                "created_at": user.created_at.isoformat(),
            }
            for user in UserRecord.objects.all()
        ]
        return JsonResponse(payload, safe=False)

    if request.method == "POST":
        body = json.loads(request.body.decode("utf-8") or "{}")
        name = str(body.get("name", "")).strip()
        email = str(body.get("email", "")).strip()
        if not name or not email:
            return JsonResponse({"detail": "Name and email are required"}, status=400)
        try:
            user = UserRecord.objects.create(name=name, email=email)
        except IntegrityError:
            return JsonResponse({"detail": "Email already exists"}, status=400)
        return JsonResponse(
            {
                "id": user.id,
                "name": user.name,
                "email": user.email,
                "created_at": user.created_at.isoformat(),
            },
            status=201,
        )

    return JsonResponse({"detail": "Method not allowed"}, status=405)
