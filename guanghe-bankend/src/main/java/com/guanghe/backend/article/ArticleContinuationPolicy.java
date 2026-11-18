package com.guanghe.backend.article;

import java.util.Set;
import java.util.regex.Pattern;

/** Short USER replies that need scoped semantic review, never authorization by their wording. */
final class ArticleContinuationPolicy {
    private static final Set<String> CONFIRMATIONS=Set.of("好","好的","可以","行","没问题","认可","同意","确认",
            "就按这个写","按这个写","开始写","直接写","写吧","生成吧","开始生成","就这样写","可以写","可以生成",
            "好，写吧","好的，写吧","好，就按这个写");
    private static final Pattern CONTINUE_CONFIRMATION=Pattern.compile("(?:好[的吧]?，?)?(?:那就|请|现在|直接)?继续(?:做|写|生成)?(?:吧)?");
    private ArticleContinuationPolicy() { }

    static boolean confirms(String text) {
        if(text==null || text.length()>40) return false;
        String normalized=text.strip().replaceAll("[。！.!]+$", "").replace(",","，");
        return CONFIRMATIONS.contains(normalized) || CONTINUE_CONFIRMATION.matcher(normalized).matches();
    }

    // Match complete short replies only. A restriction inside a writing request (e.g.
    // "不要写报价，按刚才主题生成完整新稿") is not a refusal of the requested draft.
    static boolean declinesOrOnlyAcknowledgesPlan(String text) {
        return text!=null && text.length()<=40 && (text.matches("[\\s\\p{Punct}\\p{P}]*")
                || text.matches("(?:不写|别写|先别|不要|不需要|暂时不)[。！!.]*")
                || text.matches("(?:先别写|先别生成|不生成|别生成|不要生成|不需要生成|不要写|不需要写|暂时不写|等等|稍后|好但不要生成|(?:只|仅)(?:认可|同意)(?:方案)?)[。！!.]*")
                || text.matches("(?:认可|同意|确认)(?:这个|这个的|该)?方案[。.!！]?"));
    }
}
