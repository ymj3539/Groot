package com.groot.backend.service;

import com.groot.backend.dto.request.ArticleDTO;
import com.groot.backend.dto.request.BookmarkDTO;
import com.groot.backend.dto.response.*;
import com.groot.backend.entity.*;
import com.groot.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final RegionRepository regionRepository;
    private final S3Service s3Service;
    private final RedisTemplate<String, String> redisTemplate;
    private final TagCountRepository tagCountRepository;


    @Override
    public List<String> readRegion() {
        List<RegionEntity> regions = regionRepository.findAll();
        List<String> result = new ArrayList<>();
        for(RegionEntity entity : regions){
            result.add(entity.getRegion());
        }
        return result;
    }

    @Override
    public boolean existedArticleId(Long articleId) {
        return articleRepository.existsById(articleId);
    }

    // 인기 태그 조회
    @Override
    public List<TagRankDTO> readTagRanking() {
        ZSetOperations<String, String> ZSetOperations = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> typedTuples;
        String key = "ranking";

        if(ZSetOperations.size(key) >= 10){
            typedTuples = ZSetOperations.reverseRangeWithScores(key, 0, 9);  //score순으로 10개 보여줌
        }else {
            typedTuples = ZSetOperations.reverseRangeWithScores(key, 0, ZSetOperations.size(key));
        }

        List<TagRankDTO> result = typedTuples.stream().map(TagRankDTO::convertToTagRankDTO).collect(Collectors.toList());

        // redis에 조회되는 값이 없으면 mysql에서 태그 데이터 가져오기
        if(result.size() == 0){
            // mysql-tag table 태그 이름 redis에 올리기
            List<TagEntity> tagEntities = tagRepository.findAll();

            // 태그 테이블에 데이터가 없으면 리턴
            if(tagEntities.size() == 0) return result;

            for(TagEntity tagEntity : tagEntities){
                ZSetOperations.add(key, tagEntity.getName(), 0);
            }
            ZSetOperations = redisTemplate.opsForZSet();

            if(ZSetOperations.size(key) >= 10){
                typedTuples = ZSetOperations.reverseRangeWithScores(key, 0, 9);  //score순으로 10개 보여줌
            }else {
                typedTuples = ZSetOperations.reverseRangeWithScores(key, 0, ZSetOperations.size(key));
            }

            result = typedTuples.stream().map(TagRankDTO::convertToTagRankDTO).collect(Collectors.toList());
        }else{
            // 조회되는 값은 있는데, 리셋 후 입력된 태그가 없으면 전날 데이터를 보여줌
            if(Double.compare(result.get(0).getCount(), 0.0) == 0){
                System.out.println("전날데이터");
                List<TagCountEntity> list = tagCountRepository.findAll(Sort.by(Sort.Direction.DESC, "count"));

                // tagCount 데이터가 존재하지 않으면 태그 데이터만 보여줌
                if(list.size() == 0) return result;

                List<TagRankDTO> rankDTOS = new ArrayList<>();
                if(list.size() != 0){
                    List<TagCountEntity> sublist = (list.size() >= 10) ?  list.subList(0,9) : list;

                    for(TagCountEntity tagCountEntity : sublist){
                        TagRankDTO dto = TagRankDTO.builder()
                                .tag(tagCountEntity.getTag())
                                .count(tagCountEntity.getCount())
                                .build();
                        rankDTOS.add(dto);
                    }
                }
                return rankDTOS;
            }
        }
        return result;
    }

    // @Scheduled(fixedDelay = 60000)
    @Scheduled(cron = "0 0 18 * * *", zone = "UTC") // 시간 설정 : KST - 9 (새벽 3시에 리셋)
    @Override
    public void updateTagCountTable() {
        // mysql tagcount 테이블 데이터 삭제
        tagCountRepository.deleteAll();

        // redis 내용 mysql에 저장
        String key = "ranking";
        ZSetOperations<String, String> ZSetOperations = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = ZSetOperations.reverseRangeWithScores(key, 0, ZSetOperations.size(key));  //score순으로 10개 보여줌

        for(ZSetOperations.TypedTuple typedTuple : typedTuples) {
            TagCountEntity tagCountEntity = TagCountEntity.builder()
                    .tag(typedTuple.getValue().toString())
                    .count(typedTuple.getScore())
                    .build();

            tagCountRepository.save(tagCountEntity);
        }

        // redis 리셋
        ZSetOperations.getOperations().delete(key);

        // mysql-tag table 태그 이름 redis에 올리기
        List<TagEntity> tagEntities = tagRepository.findAll();
        for(TagEntity tagEntity : tagEntities){
            ZSetOperations.add(key, tagEntity.getName(), 0);
        }

        log.info("Updated TagCount Table, reset Redis");
    }

    // 게시글 작성
    @Override
    public boolean createArticle(ArticleDTO articleDTO, String[] imgPaths) {
        String[] tags = articleDTO.getTags();
        ZSetOperations<String, String> ZSetOperations = redisTemplate.opsForZSet();

        String key = "ranking";

        // redis에 새로 insert된 태그 리스트
        List<String> newTags = new ArrayList<>();

        if(tags != null){
            // 태그가 redis에 존재하는지 탐색
            for(String tag : tags) {
                // key와 value(tag)로 tag가 redis에 있는지 확인
                Double score = ZSetOperations.score(key, tag);
                if(score == null){
                    // 없으면 redis에 저장하고 score 1증가
                    ZSetOperations.add(key, tag, 1);
                    // 새 태그 리스트에 추가
                    newTags.add(tag);
                }else {
                    // 있으면 score만 1증가
                    ZSetOperations.incrementScore(key, tag, 1);
                }

            }

            // 태그테이블에 태그 insert
            for(String tag : newTags){
                if(tagRepository.findByName(tag) == null){
                    TagEntity tagEntity = TagEntity.builder()
                            .name(tag)
                            .build();
                    tagRepository.save(tagEntity);
                }
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

        if(tags != null){
            // 태크-게시물 테이블에 insert
            for(String tag : articleDTO.getTags()){
                ArticleTagEntity articleTagEntity = ArticleTagEntity.builder()
                        .articleEntity(savedArticleEntity)
                        .tagEntity(tagRepository.findByName(tag))
                        .build();

                articleTagRepository.save(articleTagEntity);
            }
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
    public ArticleResponseDTO readArticle(Long articleId, Long userPK) {
        // ArticleEntity 조회
        ArticleEntity articleEntity = articleRepository.findById(articleId).orElseThrow();
        // UserEntity 조회
        UserEntity userEntity = userRepository.findById(articleEntity.getUserPK()).orElseThrow();
        // tags id 조회
        List<ArticleTagEntity> articleTagEntityList = articleTagRepository.findByArticleId(articleId);
        // tag id로 tagEntity 조회
        List<String> tags = new ArrayList<>();
        for(ArticleTagEntity articleTagEntity : articleTagEntityList){
            tags.add(tagRepository.findById(articleTagEntity.getTagId()).orElseThrow().getName());
        }
        // commentEntity 조회
        List<CommentEntity> commentEntityList = commentRepository.findByArticleId(articleId);

        List<CommentResponseDTO> comments = new ArrayList<>();
        int commentCnt = 0;
        // commentDTO build
        if(commentEntityList != null){
            for(CommentEntity commentEntity : commentEntityList){
                UserEntity commentUserEntity = userRepository.findById(commentEntity.getUserPK()).orElseThrow();
                CommentResponseDTO commentDTO = CommentResponseDTO.builder()
                        .userPK(commentEntity.getUserPK())
                        .profile(commentUserEntity.getProfile())
                        .content(commentEntity.getContent())
                        .createTime(commentEntity.getCreatedDate())
//                        .updateTime(commentEntity.getLastModifiedDate())
                        .build();

                comments.add(commentDTO);
            }
            commentCnt = comments.size();
        }

        // bookmark 여부 조회
        // 복합키 사용을 위한 id 등록
        ArticleBookmarkEntityPK articleBookmarkEntityPK = new ArticleBookmarkEntityPK();
        articleBookmarkEntityPK.setUserEntity(userPK);
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
        String[] tags = articleDTO.getTags();
        ZSetOperations<String, String> ZSetOperations = redisTemplate.opsForZSet();

        String key = "ranking";

        // redis에 새로 insert된 태그 리스트
        List<String> newTags = new ArrayList<>();

        // 태그가 redis에 존재하는지 탐색
        if(tags != null){
            for(String tag : tags) {
                // key와 value(tag)로 tag가 redis에 있는지 확인
                Double score = ZSetOperations.score(key, tag);
                if(score == null){
                    // 없으면 redis에 저장하고 score 1증가
                    ZSetOperations.add(key, tag, 1);
                    // 새 태그 리스트에 추가
                    newTags.add(tag);
                }else {
                    // 있으면 score만 1증가
                    ZSetOperations.incrementScore(key, tag, 1);
                }
            }
        }


        // 태그테이블에 태그 insert
        if(newTags != null){
            for(String tag : newTags){
                if(tagRepository.findByName(tag) == null){
                    TagEntity tagEntity = TagEntity.builder()
                            .name(tag)
                            .build();
                    tagRepository.save(tagEntity);
                }
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
        if(articleDTO.getTags() != null){
            for(String tag : articleDTO.getTags()){
                ArticleTagEntity articleTagEntity = ArticleTagEntity.builder()
                        .articleEntity(articleRepository.findById(savedArticleEntity.getId()).orElseThrow())
                        .tagEntity(tagRepository.findByName(tag))
                        .build();

                articleTagRepository.save(articleTagEntity);
            }
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
    public Page<ArticleListDTO> readArticleList(String category, Long userPK, Integer page, Integer size) {
        // 카테고리에 해당하는 게시글 조회
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdDate").descending());
        Page<ArticleEntity> articleEntities = articleRepository.findAllByCategory(category, pageRequest);
        Page<ArticleListDTO> result = toDtoList(articleEntities, userPK);
        return result;
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
    public Page<ArticleListDTO> filterRegion(String[] region, Long userPK, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<ArticleEntity> articleEntities = articleRepository.filterRegion(region, pageRequest);
        if(articleEntities == null){
            return null;
        }
        Page<ArticleListDTO> result = toDtoList(articleEntities, userPK);
        return result;
    }

    @Override
    public Page<ArticleListDTO> searchArticle(String category, String[] region, String keyword, Long userPK, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<ArticleEntity> articleEntities = articleRepository.search(category, region, keyword,  pageRequest);
        Page<ArticleListDTO> result = toDtoList(articleEntities, userPK);
        return result;
    }

    // 작성자가 나눔 중인 다른 나눔글
    @Override
    public List<UserSharedArticleDTO> readUserShared(Long articleId) {
        ArticleEntity articleEntity = articleRepository.findById(articleId).orElseThrow();
        Long userPK = articleEntity.getUserPK();

        List<ArticleEntity> articleEntityList = articleRepository.findUserSharedArticle(userPK, articleId);

        List<UserSharedArticleDTO> result = new ArrayList<>();
        for(ArticleEntity entity : articleEntityList){
            // 이미지 조회
            List<ArticleImageEntity> aImageEntityList = articleImageRepository.findAllByArticleId(entity.getId());

            UserSharedArticleDTO dto = UserSharedArticleDTO.builder()
                    .articleId(entity.getId())
                    .userPK(entity.getUserPK())
                    .nickName(userRepository.findById(userPK).orElseThrow().getNickName())
                    .title(entity.getTitle())
                    .img((aImageEntityList == null || aImageEntityList.size() == 0) ? null : aImageEntityList.get(0).getImg())
                    .build();

            result.add(dto);
        }

        return result;
    }

    // 마이페이지 - 유저 작성글 조회
    @Override
    public Page<ArticleListDTO> readUserArticles(Long userPK, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<ArticleEntity> articleEntities = articleRepository.findAllByUserPK(userPK, pageRequest);
        Page<ArticleListDTO> result = toDtoList(articleEntities, userPK);
        return result;
    }

    // 마이페이지 - 유저 북마크 조회
    @Override
    public Page<ArticleListDTO> readUserBookmarks(Long userPK, Integer page, Integer size) {
        List<Long> bookmarkList = articleRepository.findBookmarkByUserPK(userPK);

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdDate").descending());
        Page<ArticleEntity> articleEntities = articleRepository.findAllById(bookmarkList, pageRequest);

        return toDtoList(articleEntities, userPK);
    }

    public Page<ArticleListDTO> toDtoList(Page<ArticleEntity> articleEntities, Long userPK){

        Page<ArticleListDTO> dtoList = articleEntities.map(a ->
                ArticleListDTO.builder()
                        .articleId(a.getId())
                        .category(a.getCategory())
                        .img(findImgByArticleEntity(a))
                        .userPK(a.getUserPK())
                        .nickName(findUserEntityByArticleEntity(a).getNickName())
                        .profile(findUserEntityByArticleEntity(a).getProfile())
                        .title(a.getTitle())
                        .tags(findTagsByArticleEntity(a))
                        .views(a.getViews())
                        .commentCnt(findCommentCntByArticleEntity(a))
                        .bookmark(findBookmarkByArticleEntity(a, userPK))
                        .shareRegion(a.getShareRegion())
                        .shareStatus(a.getShareStatus())
                        .createTime(a.getCreatedDate())
                        .updateTime(a.getLastModifiedDate())
                        .build());
        return dtoList;
    }

    // 북마크 조회 함수
    public boolean findBookmarkByArticleEntity(ArticleEntity articleEntity, Long userPK){
        // 복합키 사용을 위한 id 등록
        ArticleBookmarkEntityPK articleBookmarkEntityPK = new ArticleBookmarkEntityPK();
        articleBookmarkEntityPK.setUserEntity(userPK);
        articleBookmarkEntityPK.setArticleEntity(articleEntity.getId());
        boolean bookmark;
        if(aBookmarkRepo.findById(articleBookmarkEntityPK).isPresent()){
            bookmark = true;
        }else bookmark = false;

        return bookmark;
    }

    // 댓글 수 조회 함수
    public int findCommentCntByArticleEntity(ArticleEntity articleEntity){
        // 댓글수 조회
        int commentCnt;
        List<CommentEntity> commentEntityList = commentRepository.findByArticleId(articleEntity.getId());
        if(commentEntityList == null){
            commentCnt = 0;
        }else commentCnt = commentEntityList.size();

        return commentCnt;
    }


    // 태그 조회 함수
    public List<String> findTagsByArticleEntity(ArticleEntity articleEntity){
        List<String> tags = new ArrayList<>();
        List<ArticleTagEntity> articleTagEntityList = articleTagRepository.findByArticleId(articleEntity.getId());

        if (articleTagEntityList.size() == 0) return tags;

        for(ArticleTagEntity entity : articleTagEntityList){
            tags.add(tagRepository.findById(entity.getTagId()).orElseThrow().getName());
        }
        return tags;
    }


    // 게시글 entity로 작성자 entity 반환
    public UserEntity findUserEntityByArticleEntity(ArticleEntity articleEntity){
        return userRepository.findById(articleEntity.getUserPK()).orElseThrow();
    }

    // 이미지 조회 함수
    public String findImgByArticleEntity (ArticleEntity articleEntity){
        // 이미지 조회
        List<ArticleImageEntity> articleImageEntityList = articleImageRepository.findAllByArticleId(articleEntity.getId());
        String imgPath = null;
        if(articleImageEntityList != null && articleImageEntityList.size() !=0){
            // 첫번째 이미지 가져오기
            imgPath = articleImageEntityList.get(0).getImg();
        }
        return imgPath;
    }


}
