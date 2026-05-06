package baize.code.java.service.impl;

import baize.code.java.code.ResultCode;
import baize.code.java.common.Result;
import baize.code.java.service.GoodsDocumentService;
import baize.code.java.utils.FileToDocuments;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import baize.code.java.entity.GoodsDocument;
import baize.code.java.mapper.GoodsDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static baize.code.java.code.DocumentCode.FILE_ID;
import static baize.code.java.code.DocumentCode.GOODS_ID;

@Service
@RequiredArgsConstructor
public class GoodsDocumentServiceImpl extends ServiceImpl<GoodsDocumentMapper, GoodsDocument> implements GoodsDocumentService {

    private final MilvusVectorStore vectorStore;

    @Autowired
    private FileToDocuments fileToDocuments;
    @Override
    public List<GoodsDocument> getListByGoodsId(Integer id) {
        return lambdaQuery().eq(GoodsDocument::getGoodsId, id).list();
    }

    @Override
    public Result<?> upload(MultipartFile file, Integer goodsId) {
        // 1 文档转换为document对象
        List<Document> documents = fileToDocuments.handle(file);
        System.out.println(documents);
        //2文件存储数据库
        GoodsDocument goodsDocument = GoodsDocument.builder()
                .goodsId(goodsId)
                .name(file.getOriginalFilename())
                .build();
        if(!save(goodsDocument)){
           return Result.error(ResultCode.UPLOAD_DOCUMENT_ERROR);
        }
        // 3 文档向量化
        // 设置元数据
        documents.forEach(d -> {
            // 数据库的主键id
            d.getMetadata().put(FILE_ID,goodsDocument.getId().toString());
            //当前文档属于哪一件商品
            d.getMetadata().put(GOODS_ID,goodsId.toString());
        });
        //将文档存储到向量数据库中vectorStore 存储 每次只能处理10条
        for (int i = 0; i < documents.size(); i+=10) {
            int endIndex = Math.min(i+10,documents.size());
            List<Document> batch = documents.subList(i, endIndex);
            vectorStore.add(batch);
        }
        return  Result.success(ResultCode.ADD_SUCCESS,goodsDocument);
    }

    @Override
    public Result<?> delete(Integer id) {
        GoodsDocument goodsDocument = getById(id);
        if (goodsDocument == null ){
            return  Result.error(ResultCode.DELETE_ERROR);
        }
        if(!removeById(id)){
            return  Result.error(ResultCode.DELETE_ERROR);
        }

        // 删除向量数据库
        vectorStore.delete(new Filter.Expression(Filter.ExpressionType.EQ,new Filter.Key(FILE_ID),new Filter.Value(id)));

        return Result.success(ResultCode.DELETE_SUCCESS);
    }
}
