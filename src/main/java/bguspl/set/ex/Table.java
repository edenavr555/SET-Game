package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.w3c.dom.views.AbstractView;

import bguspl.set.UserInterface;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

     /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Boolean[][] tokens; // slot per card (if any)

    protected AtomicBoolean removingCards;

    protected int activePlayers;
    protected int activeDealer;
    protected int waitingDealer;





    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.tokens = new Boolean[slotToCard.length][env.config.players];
        this.removingCards=new AtomicBoolean(false);
        this.activeDealer=0;
        this.activePlayers=0;
        this.waitingDealer=0;
        for(int i=0; i<tokens.length; i++){
            for(int j=0; j<tokens[0].length; j++){
                tokens[i][j]=false;
            }
        }
        for(Integer slot: slotToCard)
            slot = null;
        for(Integer card: cardToSlot)
            card=null;
        
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {};
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
        // TODO implement
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {};
        int card = slotToCard[slot];
        slotToCard[slot] = null;
        cardToSlot[card] = null;
        env.ui.removeCard(slot);
        }

        // TODO implement

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        tokens[slot][player]=true;
        env.ui.placeToken(player, slot);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        if(tokens[slot][player]==true){
            tokens[slot][player]=false;
            env.ui.removeToken(player, slot); //need to check if requiers a pre-check if there's a token
            return true;
        }
        return false;
    }

     public int getCard(int slot) {
        if(slot<0 || slot>slotToCard.length){
            throw new IllegalArgumentException("slot does not exist");
        }
        if(slotToCard[slot]==null)
            return -1;
        return slotToCard[slot];
    }

    public void clearCards(){
        for(int i=0; i<env.config.tableSize; i++){
            slotToCard[i] = null;
            env.ui.removeCard(i);

        }
        for(int i=0; i<env.config.deckSize; i++){
            cardToSlot[i] = null;
        }
    }


    //------READER-WRITER-LOCK-------//
    protected synchronized void beforeRead() {
        while (!(waitingDealer == 0 && activeDealer == 0))
        try{
            wait();
        }
        catch(InterruptedException e){break;};
        activePlayers++;
    }
    
    protected synchronized void afterRead() {
        activePlayers--;
        notifyAll();
    }
    
    protected synchronized void beforeWrite() {
        waitingDealer=1;
        while (!(activePlayers == 0 && activeDealer == 0))
        try{
            wait();
        }
        catch(InterruptedException e){break;};
        waitingDealer=0;
        activeDealer=1;
    }
    
    protected synchronized void afterWrite() {
        activeDealer=0;
        notifyAll();
    }    


}
