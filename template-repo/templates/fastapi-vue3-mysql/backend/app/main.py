from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .config import settings
from .database import Base, SessionLocal, engine, get_db
from .models import User
from .schemas import UserCreate, UserRead


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Initialize tables and seed one record so a newly generated project can
    # start successfully before any custom business data is added.
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        if not db.query(User).filter(User.email == "admin@example.com").first():
            db.add(User(name="Template Admin", email="admin@example.com"))
            db.commit()
    yield


app = FastAPI(title=settings.app_name, version="1.0.0", lifespan=lifespan)


@app.get("/api/health")
def get_health():
    return {
        "status": "UP",
        "service": settings.app_name,
        "databaseUrl": settings.database_url,
    }


@app.get("/api/users", response_model=list[UserRead])
def list_users(db: Session = Depends(get_db)):
    # Keep read endpoints small and explicit. This is the baseline style the
    # code generator should imitate for simple list APIs.
    return db.query(User).order_by(User.created_at.desc()).all()


@app.post("/api/users", response_model=UserRead, status_code=201)
def create_user(payload: UserCreate, db: Session = Depends(get_db)):
    # Build the ORM entity from the validated schema in one visible block so
    # later field expansion stays easy to read.
    entity = User(name=payload.name, email=payload.email)
    db.add(entity)
    try:
        db.commit()
    except IntegrityError:
        # Convert storage-specific failures into a business-friendly response.
        db.rollback()
        raise HTTPException(status_code=400, detail="Email already exists")
    db.refresh(entity)
    return entity
