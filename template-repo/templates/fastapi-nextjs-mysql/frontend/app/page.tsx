"use client";

import { FormEvent, useEffect, useState } from "react";

type User = {
  id: number;
  name: string;
  email: string;
  created_at: string;
};

export default function HomePage() {
  const [users, setUsers] = useState<User[]>([]);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function loadUsers() {
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/users", { cache: "no-store" });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.detail ?? "Failed to load users");
      }
      setUsers(payload);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Failed to load users");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadUsers();
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const response = await fetch("/api/users", {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ name, email })
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload?.detail ?? "Failed to create user");
      }
      setUsers((current) => [payload, ...current]);
      setName("");
      setEmail("");
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "Failed to create user");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page">
      <section className="hero">
        <p className="eyebrow">Template Repo v1</p>
        <h1>__DISPLAY_NAME__</h1>
        <p>
          FastAPI handles the API and persistence layer, while Next.js powers a
          modern React frontend. Use this as a direct starting point for admin
          tools, dashboards, and internal platforms.
        </p>
      </section>

      <section className="grid">
        <article className="panel">
          <h2>User List</h2>
          <div className="metric">Total {users.length} users</div>
          {loading ? <p className="muted">Loading users...</p> : null}
          {!loading && users.length === 0 ? (
            <p className="muted">The API is connected, but no user records are available yet.</p>
          ) : null}
          <ul className="user-list">
            {users.map((user) => (
              <li className="user-item" key={user.id}>
                <div>
                  <strong>{user.name}</strong>
                  <div className="muted">{user.email}</div>
                </div>
                <span className="badge">READY</span>
              </li>
            ))}
          </ul>
        </article>

        <article className="panel">
          <h2>Create User</h2>
          <form className="form" onSubmit={handleSubmit}>
            <div className="field">
              <label htmlFor="name">Name</label>
              <input
                id="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Template Admin"
                required
              />
            </div>
            <div className="field">
              <label htmlFor="email">Email</label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="admin@example.com"
                required
              />
            </div>
            <button className="button" disabled={submitting} type="submit">
              {submitting ? "Submitting..." : "Create User"}
            </button>
            {error ? <p className="error">{error}</p> : null}
          </form>
        </article>
      </section>
    </main>
  );
}
