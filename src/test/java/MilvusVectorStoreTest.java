import baize.code.java.ServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest(classes = ServerApplication.class)
public class MilvusVectorStoreTest {

    @Autowired
    private VectorStore vectorStore;


    @Test
    public void testQuery() {
        String query = "负责人";
        List<Document> documents = vectorStore.similaritySearch(query);
        for (Document document : documents) {
            System.out.println(document);
        }
    }
}
