package com.localrepo.server;

import org.springframework.stereotype.Controller;

import java.io.PrintWriter;

@Controller
public class URLController {


    private PrintWriter writer;

    public URLController(PrintWriter writer) {
        this.writer = writer;
    }

    public void getDependency(String path) {
        writer.println(path);
    }
}
