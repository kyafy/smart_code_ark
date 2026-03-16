from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field


class ApiResponse(BaseModel):
    code: int = 0
    message: str = "OK"
    data: dict | None = None


class BusinessException(Exception):
    def __init__(self, code: int, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(message)


class RequirementParseRequest(BaseModel):
    text: str = Field(min_length=2, description="用户输入需求")


class RequirementParseResult(BaseModel):
    projectName: str
    modules: list[str]
    techStack: list[str]


app = FastAPI(title="requirement-service", version="0.1.0")


@app.exception_handler(BusinessException)
async def handle_business_exception(_: Request, exc: BusinessException) -> JSONResponse:
    return JSONResponse(
        status_code=400,
        content=ApiResponse(code=exc.code, message=exc.message, data=None).model_dump(),
    )


@app.exception_handler(Exception)
async def handle_unknown_exception(_: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(
        status_code=500,
        content=ApiResponse(code=50000, message=str(exc), data=None).model_dump(),
    )


@app.get("/health")
async def health() -> ApiResponse:
    return ApiResponse(data={"service": "requirement-service", "status": "UP"})


@app.post("/api/v1/requirements/parse")
async def parse_requirement(req: RequirementParseRequest) -> ApiResponse:
    text = req.text.strip()
    if len(text) < 2:
        raise BusinessException(40001, "需求描述过短")

    module_candidates = ["用户", "项目", "任务", "生成", "管理", "统计", "计费", "对话"]
    modules = [name for name in module_candidates if name in text]
    if not modules:
        modules = ["用户", "项目", "生成"]

    tech_stack = ["Java", "Spring Boot", "FastAPI", "PostgreSQL", "Redis"]
    result = RequirementParseResult(
        projectName="AI毕业设计代码生成平台",
        modules=modules,
        techStack=tech_stack,
    )
    return ApiResponse(data=result.model_dump())
