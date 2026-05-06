package baize.code.java.utils;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class FileToDocuments {

    public List<Document> handle(MultipartFile multipartFile){

        //调用处理方法
        FileHandler fileHandler = fileFilter(multipartFile);
        return fileHandler.run(multipartFile);

    }

    private FileHandler fileFilter(MultipartFile file){
        String filename = file.getOriginalFilename();
        if(filename == null){
            throw new RuntimeException("文件名称不能为空");
        }

        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return switch (extension){
            case "md", "markdown" -> new MarkdownFileHandler();
            case "pdf" -> new PdfFileHandler();
            case "txt" -> new TextFileHandler();
            default -> throw new IllegalArgumentException("不支持的文件类型: " + extension);
        };

    }

    public interface FileHandler {
        List<Document> run (MultipartFile file);

    }

    private static class MarkdownFileHandler implements FileHandler{

        @Override
        public List<Document> run(MultipartFile file) {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(false) // 不按照分割线创建新的document
                    .withIncludeBlockquote(false) // 不按照代码块创建信息的document
                    .build();
            Resource resource;
            try {
                resource = new ByteArrayResource(file.getBytes());
            } catch (IOException e) {
                throw new RuntimeException("Markdown读取文件失败"+e);
            }
            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            List<Document> documents = reader.read();
            documents = ChineseTokenTextSplitter.quicklyBuilder().split(documents);
            return documents;
        }
    }

    private static class PdfFileHandler implements FileHandler{
        @Override
        public List<Document> run(MultipartFile file) {

            Resource resource;
            try {
               resource = new ByteArrayResource(file.getBytes());
            } catch (IOException e) {
                throw new RuntimeException("Pdf读取文件失败"+e);
            }
            // 一页一个document
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource);
            List<Document> documents = reader.read();
            documents = ChineseTokenTextSplitter.quicklyBuilder().split(documents);
            return documents;
        }
    }


    private static class TextFileHandler implements FileHandler {
        @Override
        public List<Document> run(MultipartFile file) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                List<Document> documents = new ArrayList<>(1);
                documents.add(new Document(content.toString()));
                documents = ChineseTokenTextSplitter.quicklyBuilder().split(documents);
                return documents;
            } catch (IOException e) {
                throw new RuntimeException("读取文本文件失败", e);
            }
        }
    }

}
