package baize.code.java.controller;

import baize.code.java.common.Result;
import baize.code.java.entity.User;
import baize.code.java.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @Autowired
    private VectorStore vectorStore;

    @PostMapping("/login")
    public Result<?> login(@RequestBody User user){
        return userService.login(user);
    }

    @GetMapping("/test")
    public Result<?> testVectorStore(@RequestParam("query") String query){
        List<Document> documents = vectorStore.similaritySearch(query);
        for (Document document : documents) {
            System.out.println(document);
        }
        return null;
    }
}
