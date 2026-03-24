package com.smartark.template.mobile.news;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    @GetMapping
    public List<NewsItem> listNews() {
        return List.of(
                new NewsItem(1L, "欢迎使用模板仓库", "这是一个适合作为 UniApp 首屏骨架的接口示例。", "查看详情"),
                new NewsItem(2L, "下一步建议", "接入登录、配置环境变量，并替换为你的真实业务接口。", "开始改造")
        );
    }
}
