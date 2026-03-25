"""
Example Django tests using TestCase.
LLM should follow this pattern when generating tests for new apps.
"""
from django.test import TestCase, Client
from django.urls import reverse
from .models import User


class UserModelTest(TestCase):
    """Tests for the User model."""

    def test_create_user(self):
        """User creation should persist to database."""
        user = User.objects.create(
            name="Test User",
            email="test@example.com"
        )
        self.assertEqual(user.name, "Test User")
        self.assertEqual(user.email, "test@example.com")
        self.assertIsNotNone(user.pk)

    def test_str_representation(self):
        """__str__ should return a meaningful string."""
        user = User.objects.create(name="Alice", email="alice@example.com")
        self.assertIn("Alice", str(user))


class UserApiTest(TestCase):
    """Tests for User API endpoints."""

    def setUp(self):
        self.client = Client()

    def test_list_users_empty(self):
        """GET /api/users/ should return empty list initially."""
        resp = self.client.get("/api/users/")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), [])

    def test_create_user(self):
        """POST /api/users/ should create a new user."""
        resp = self.client.post(
            "/api/users/",
            data={"name": "Bob", "email": "bob@example.com"},
            content_type="application/json",
        )
        self.assertIn(resp.status_code, [200, 201])

    def test_create_user_invalid(self):
        """POST /api/users/ with missing fields should fail."""
        resp = self.client.post(
            "/api/users/",
            data={},
            content_type="application/json",
        )
        self.assertIn(resp.status_code, [400, 422])
