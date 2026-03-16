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


class RequirementAnalysis(BaseModel):
    projectName: str = Field(min_length=1)
    modules: list[str]
    techStack: list[str]


class GenerateRequest(BaseModel):
    analysis: RequirementAnalysis


class GenerateResult(BaseModel):
    summary: str
    generatedFiles: list[str]


app = FastAPI(title="generation-service", version="0.1.0")


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
    return ApiResponse(data={"service": "generation-service", "status": "UP"})


@app.post("/api/v1/generations/scaffold")
async def scaffold(req: GenerateRequest) -> ApiResponse:
    modules = req.analysis.modules
    if not modules:
        raise BusinessException(40002, "模块列表不能为空")

    generated_files: list[str] = []
    for module in modules:
        safe_module = module.lower()
        generated_files.append(f"services/{safe_module}-service/src/main/java/{module}Controller.java")
        generated_files.append(f"services/{safe_module}-service/src/main/java/{module}Service.java")

    result = GenerateResult(
        summary=f"已按模块生成 {len(modules)} 组服务骨架，项目名：{req.analysis.projectName}",
        generatedFiles=generated_files,
    )
    return ApiResponse(data=result.model_dump())
