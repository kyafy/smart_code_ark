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
        // Keep the first mobile example endpoint static and easy to read so the
        // generated project has a working API before real storage is added.
        return List.of(
                new NewsItem(
                        1L,
                        "Welcome To The Starter Template",
                        "This endpoint gives the mobile homepage a simple API example to render immediately.",
                        "View Details"
                ),
                new NewsItem(
                        2L,
                        "Suggested Next Step",
                        "Replace this mock feed with your own business API after login, environment, and routing are ready.",
                        "Start Customizing"
                )
        );
    }
}
