package com.groot.backend.service;

import com.groot.backend.dto.request.ArticleDTO;
import com.groot.backend.dto.request.BookmarkDTO;
import com.groot.backend.dto.response.ArticleListDTO;
import com.groot.backend.dto.response.ArticleResponseDTO;
import com.groot.backend.dto.response.CommentDTO;
import com.groot.backend.entity.*;
import com.groot.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ArticleServiceImpl implements ArticleService{
    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;
    private final ArticleTagRepository articleTagRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final ArticleImageRepository articleImageRepository;
    private final ArticleBookmarkRepository aBookmarkRepo;
    private final S3Service s3Service;

    @Override
    public boolean existedArticleId(Long articleId) {
        return articleRepository.existsById(articleId);
    }

    // 게시글 작성
    @Override
    public boolean createArticle(ArticleDTO articleDTO, String[] imgPaths) {
        // 태그가 redis에 존재하는지 탐색

        // redis에 태그 insert
        // redis에 새로 insert된 태그 리스트
        String[] newTags = articleDTO.getTags();

        // 태그테이블에 태그 insert
        for(String tag : newTags){
            if(tagRepository.findByName(tag) == null){
                TagEntity tagEntity = TagEntity.builder()
                        .name(tag)
                        .build();

                tagRepository.save(tagEntity);
            }
        }

        // article 테이블에 insert
        ArticleEntity articleEntity = ArticleEntity.builder()
                .category(articleDTO.getCategory())
                .userEntity(userRepository.findById(articleDTO.getUserPK()).orElseThrow())
                .title(articleDTO.getTitle())
                .content(articleDTO.getContent())
                .views(0L)
                .shareStatus(articleDTO.getShareStatus())
                .shareRegion(articleDTO.getShareRegion())
                .build();

        ArticleEntity savedArticleEntity = articleRepository.save(articleEntity);

        // 태크-게시물 테이블에 insert
        for(String tag : articleDTO.getTags()){
            ArticleTagEntity articleTagEntity = ArticleTagEntity.builder()
                    .articleEntity(savedArticleEntity)
                    .tagEntity(tagRepository.findByName(tag))
                    .build();

            articleTagRepository.save(articleTagEntity);
        }

        // 이미지 테이블에 게시글PK + 이미지주소 insert
        if(imgPaths != null){
            for(String imgPath : imgPaths){
                ArticleImageEntity articleImageEntity = ArticleImageEntity.builder()
                        .articleEntity(savedArticleEntity)
                        .img(imgPath)
                        .build();

                articleImageRepository.save(articleImageEntity);
            }
        }


        return true;
    }

    @Override
    public ArticleResponseDTO readArticle(Long articleId) {
        // ArticleEntity 조회
        ArticleEntity articleEntity = articleRepository.findById(articleId).orElseThrow();
        // UserEntity 조회
        UserEntity userEntity = userRepository.findById(articleEntity.getUserPK()).orElseThrow();
        // tags id 조회
        List<ArticleTagEntity> articleTagEntityList = (List<ArticleTagEntity>) articleTagRepository.findByArticleId(articleId);
        // tag id로 tagEntity 조회
        List<String> tags = new ArrayList<>();
        for(ArticleTagEntity articleTagEntity : articleTagEntityList){
            tags.add(tagRepository.findById(articleTagEntity.getTagId()).orElseThrow().getName());
        }
        // commentEntity 조회
        List<CommentEntity> commentEntityList = (List<CommentEntity>) commentRepository.findByArticleId(articleId);

        List<CommentDTO> comments = new ArrayList<>();
        int commentCnt = 0;
        // commentDTO build
        if(commentEntityList != null){
            for(CommentEntity commentEntity : commentEntityList){
                UserEntity commentUserEntity = userRepository.findById(commentEntity.getUserPK()).orElseThrow();
                CommentDTO commentDTO = CommentDTO.builder()
                        .userPK(commentEntity.getUserPK())
                        .profile(commentUserEntity.getProfile())
                        .content(commentEntity.getContent())
                        .createTime(commentEntity.getCreatedDate())
                        .updateTime(commentEntity.getLastModifiedDate())
                        .build();

                comments.add(commentDTO);
            }
            commentCnt = comments.size();
        }

        // bookmark 여부 조회
        // 복합키 사용을 위한 id 등록
        ArticleBookmarkEntityPK articleBookmarkEntityPK = new ArticleBookmarkEntityPK();
        articleBookmarkEntityPK.setUserEntity(userEntity.getId());
        articleBookmarkEntityPK.setArticleEntity(articleId);

        boolean bookmark;
        if(aBookmarkRepo.findById(articleBookmarkEntityPK).isPresent()){
            bookmark = true;
        }else bookmark = false;

        // image 조회
        List<String> imgPaths = new ArrayList<>();
        List<ArticleImageEntity> articleImageEntityList = articleImageRepository.findAllByArticleId(articleId);
        if(articleImageEntityList != null){
            for(ArticleImageEntity entity : articleImageEntityList){
                imgPaths.add(entity.getImg());
            }
        }

        ArticleResponseDTO articleResponseDTO = ArticleResponseDTO.builder()
                .category(articleEntity.getCategory())
                .imgs(imgPaths)
                .userPK(articleEntity.getUserPK())
                .nickName(userEntity.getNickName())
                .profile(userEntity.getProfile())
                .title(articleEntity.getTitle())
                .tags(tags)
                .views(articleEntity.getViews()+1)
                .commentCnt(commentCnt)
                .bookmark(bookmark)
                .shareRegion(articleEntity.getShareRegion())
                .content(articleEntity.getContent())
                .shareStatus(articleEntity.getShareStatus())
                .createTime(articleEntity.getCreatedDate())
                .updateTime(articleEntity.getLastModifiedDate())
                .comments(comments)
                .build();

        // 조회수 업데이트
        ArticleEntity newArticleEntity = ArticleEntity.builder()
                .id(articleEntity.getId())
                .category(articleEntity.getCategory())
                .userEntity(userRepository.findById(articleEntity.getUserPK()).orElseThrow())
                .title(articleEntity.getTitle())
                .content(articleEntity.getContent())
                .views(articleEntity.getViews()+1)
                .shareStatus(articleEntity.getShareStatus())
                .shareRegion(articleEntity.getShareRegion())
                .build();

        articleRepository.save(newArticleEntity);



        return articleResponseDTO;
    }

    @Override
    public boolean updateArticle(ArticleDTO articleDTO, String[] imgPaths) {
        // redis에 존재하는지 탐색

        // redis에 태그 insert
        // redis에 새로 insert된 태그 리스트
        String[] tags = articleDTO.getTags();
        List<String> newTags = new ArrayList<>();
        for(String tag : tags){
            if(tagRepository.findByName(tag) == null){
                newTags.add(tag);
            }
        }

        // 태그테이블에 태그 insert
        if(newTags != null){
            for(String tag : newTags){
                TagEntity tagEntity = TagEntity.builder()
                        .name(tag)
                        .build();

                tagRepository.save(tagEntity);
            }
        }

        // 태크-게시물 테이블에 기존 태그 delete
        List<ArticleTagEntity> articleTagEntityList = articleTagRepository.findByArticleId(articleDTO.getArticleId());
        for(ArticleTagEntity articleTagEntity : articleTagEntityList){
            articleTagRepository.delete(articleTagEntity);
        }

        // 이미지 테이블의 기존 정보 delete
        List<ArticleImageEntity> articleImageEntityList = articleImageRepository.findAllByArticleId(articleDTO.getArticleId());
        for(ArticleImageEntity articleImageEntity : articleImageEntityList){
            articleImageRepository.delete(articleImageEntity);
        }

        // article 테이블에 update
        ArticleEntity articleEntity = articleRepository.findById(articleDTO.getArticleId()).orElseThrow();
        ArticleEntity newArticleEntity = ArticleEntity.builder()
                .id(articleDTO.getArticleId())
                .category(articleDTO.getCategory())
                .userEntity(userRepository.findById(articleDTO.getUserPK()).orElseThrow())
                .title(articleDTO.getTitle())
                .content(articleDTO.getContent())
                .views(articleEntity.getViews())
                .shareStatus(articleDTO.getShareStatus())
                .shareRegion(articleDTO.getShareRegion())
                .build();

        ArticleEntity savedArticleEntity = articleRepository.save(newArticleEntity);

        // 태그-게시물 테이블에 insert
        for(String tag : articleDTO.getTags()){
            ArticleTagEntity articleTagEntity = ArticleTagEntity.builder()
                    .articleEntity(articleRepository.findById(savedArticleEntity.getId()).orElseThrow())
                    .tagEntity(tagRepository.findByName(tag))
                    .build();

            articleTagRepository.save(articleTagEntity);
        }

        // 이미지 테이블에 insert
        if(imgPaths != null){
            for(String imgPath : imgPaths){
                ArticleImageEntity articleImageEntity = ArticleImageEntity.builder()
                        .articleEntity(savedArticleEntity)
                        .img(imgPath)
                        .build();

                articleImageRepository.save(articleImageEntity);
            }
        }

        return true;
    }

    @Override
    public void deleteArticle(Long articleId) {
        // s3 이미지 삭제
        List<ArticleImageEntity> articleImageEntityList = articleImageRepository.findAllByArticleId(articleId);
        if(articleImageEntityList != null){
            for(ArticleImageEntity entity : articleImageEntityList){
                s3Service.delete(entity.getImg());
            }
        }
        articleRepository.deleteById(articleId);
    }

    @Override
    public Page<ArticleListDTO> readArticleList(String category, Integer page, Integer size) {
        // 카테고리에 해당하는 게시글 조회
        List<ArticleEntity> articleEntities = articleRepository.findAllByCategory(category);
        return entityListToResponseDTOPage(articleEntities, page, size);
    }

    @Override
    public void updateBookMark(BookmarkDTO bookmarkDTO) {
        Long articleId = bookmarkDTO.getArticleId();
        Long userPK = bookmarkDTO.getUserPK();
        Boolean bStatus = bookmarkDTO.getBookmarkStatus();

        // bookmark 여부 조회
        // 복합키 사용을 위한 id 등록
        ArticleBookmarkEntityPK aBookmarkEntityPK = new ArticleBookmarkEntityPK();
        aBookmarkEntityPK.setUserEntity(userPK);
        aBookmarkEntityPK.setArticleEntity(articleId);

        if(bStatus){
            // 해제
            if(aBookmarkRepo.findById(aBookmarkEntityPK).isPresent()){
                aBookmarkRepo.delete(aBookmarkRepo.findById(aBookmarkEntityPK).orElseThrow());
            }

        }else{
            // 등록
            if(!aBookmarkRepo.findById(aBookmarkEntityPK).isPresent()){
                ArticleBookmarkEntity aBookmarkEntity = ArticleBookmarkEntity.builder()
                        .articleEntity(articleRepository.findById(articleId).orElseThrow())
                        .userEntity(userRepository.findById(userPK).orElseThrow())
                        .build();
                aBookmarkRepo.save(aBookmarkEntity);
            }
        }

    }

    @Override
    public Page<ArticleListDTO> filterRegion(String[] region, Integer page, Integer size) {
        List<ArticleEntity> articleEntityList = articleRepository.filterRegion(region);

        return entityListToResponseDTOPage(articleEntityList, page, size);
    }

    @Override
    public Page<ArticleListDTO> searchArticle(String keyword, Integer page, Integer size) {
        List<ArticleEntity> articleEntityList = articleRepository.search(keyword);
        return entityListToResponseDTOPage(articleEntityList, page, size);
    }

    // articleEntityList to articleListDTOPage
    public Page<ArticleListDTO> entityListToResponseDTOPage(List<ArticleEntity> articleEntityList, Integer page, Integer size) {
        List<ArticleListDTO> articleListDTOList = new ArrayList<>();  // response DTO list

        for(ArticleEntity articleEntity : articleEntityList){
            // 이미지 조회
            List<ArticleImageEntity> articleImageEntityList = articleImageRepository.findAllByArticleId(articleEntity.getId());
            String imgPath = null;
            if(articleImageEntityList != null && articleImageEntityList.size() !=0){
                // 첫번째 이미지 가져오기
                imgPath = articleImageEntityList.get(0).getImg();
            }
            // 유저 조회
            UserEntity userEntity = userRepository.findById(articleEntity.getUserPK()).orElseThrow();
            // 태그 조회
            List<String> tags = new ArrayList<>();
            List<ArticleTagEntity> articleTagEntityList = articleTagRepository.findByArticleId(articleEntity.getId());
            for(ArticleTagEntity entity : articleTagEntityList){
                tags.add(tagRepository.findById(entity.getTagId()).orElseThrow().getName());
            }
            // 댓글수 조회
            int commentCnt;
            List<CommentEntity> commentEntityList = (List<CommentEntity>) commentRepository.findByArticleId(articleEntity.getId());
            if(commentEntityList == null){
                commentCnt = 0;
            }else commentCnt = commentEntityList.size();
            // bookmark 여부 조회
            // 복합키 사용을 위한 id 등록
            ArticleBookmarkEntityPK articleBookmarkEntityPK = new ArticleBookmarkEntityPK();
            articleBookmarkEntityPK.setUserEntity(userEntity.getId());
            articleBookmarkEntityPK.setArticleEntity(articleEntity.getId());
            boolean bookmark;
            if(aBookmarkRepo.findById(articleBookmarkEntityPK).isPresent()){
                bookmark = true;
            }else bookmark = false;

            // articleListDTO builder
            ArticleListDTO articleListDTO = ArticleListDTO.builder()
                    .articleId(articleEntity.getId())
                    .category(articleEntity.getCategory())
                    .img(imgPath)
                    .userPK(articleEntity.getUserPK())
                    .nickName(userEntity.getNickName())
                    .profile(userEntity.getProfile())
                    .title(articleEntity.getTitle())
                    .tags(tags)
                    .views(articleEntity.getViews())
                    .commentCnt(commentCnt)
                    .bookmark(bookmark)
                    .shareRegion(articleEntity.getShareRegion())
                    .shareStatus(articleEntity.getShareStatus())
                    .createTime(articleEntity.getCreatedDate())
                    .updateTime(articleEntity.getLastModifiedDate())
                    .build();

            articleListDTOList.add(articleListDTO);
        }

        // pagination
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        int start = (int) pageRequest.getOffset();
        int end = Math.min((start+pageRequest.getPageSize()), articleListDTOList.size());
        if(start > end){
            return null;
        }
        Page<ArticleListDTO> articleListDTOPage = new PageImpl<>(articleListDTOList.subList(start, end), pageRequest, articleListDTOList.size());
        return articleListDTOPage;
    }
}
