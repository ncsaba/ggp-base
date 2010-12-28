package player.gamer.statemachine;

import java.util.ArrayList;
import java.util.List;

import player.gamer.Gamer;
import player.gamer.exception.MetaGamingException;
import player.gamer.exception.MoveSelectionException;
import util.gdl.grammar.GdlSentence;
import util.logging.GamerLogger;
import util.statemachine.MachineState;
import util.statemachine.Move;
import util.statemachine.Role;
import util.statemachine.StateMachine;
import util.statemachine.exceptions.GoalDefinitionException;
import util.statemachine.exceptions.MoveDefinitionException;
import util.statemachine.exceptions.TransitionDefinitionException;

/**
 * The base class for Gamers that rely on representing games as state machines.
 * Almost every player should subclass this class, since it provides the common
 * methods for interpreting the match history as transitions in a state machine,
 * and for keeping an up-to-date view of the current state of the game.
 * 
 * See @SimpleSearchLightGamer, @HumanGamer, and @LegalGamer for examples.
 * 
 * @author evancox
 * @author Sam
 */
public abstract class StateMachineGamer extends Gamer
{
    // =====================================================================
    // First, the abstract methods which need to be overriden by subclasses.
    // These determine what state machine is used, what the gamer does during
    // metagaming, and how the gamer selects moves.

    /**
     * Defines which state machine this gamer will use.
     * @return
     */
    public abstract StateMachine getInitialStateMachine();    
    
    /**
     * Defines the metagaming action taken by a player during the START_CLOCK
     * @param timeout the START_CLOCK for the current game
     * @throws TransitionDefinitionException
     * @throws MoveDefinitionException
     * @throws GoalDefinitionException
     */    
    public abstract void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException;
    
    /**
     * Defines the algorithm that the player uses to select their move.
     * @param timeout the START_CLOCK for the current game
     * @return Move - the move selected by the player
     * @throws TransitionDefinitionException
     * @throws MoveDefinitionException
     * @throws GoalDefinitionException
     */
    public abstract Move stateMachineSelectMove(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException;

    // =====================================================================
    // Next, methods which can be used by subclasses to get information about
    // the current state of the game, and tweak the state machine on the fly.
    
	/**
	 * Returns the current state
	 * @return the current state
	 */
	public final MachineState getCurrentState()
	{
		return currentState;
	}
	
	/**
	 * Returns the current role
	 * @return the current role
	 */
	public final Role getRole()
	{
		return role;
	}
	
	/**
	 * Returns the state machine.  This is used for calculating the next state and other operations such as computing
	 * the legal moves for all players
	 * 
	 * @return a state machine
	 */
	public final StateMachine getStateMachine()
	{
		return stateMachine;
	}
	
    /**
     * Cleans up the role, currentState and stateMachine. This should only be
     * used when a match is over, and even then only when you really need to
     * free up resources that the state machine has tied up. Currently, it is
     * only used in the Proxy, for players designed to run 24/7.
     */
    protected final void cleanupAfterMatch() {
        role = null;
        currentState = null;        
        stateMachine = null;
        setMatch(null);
        setRoleName(null);
    }
    
    /**
     * Switches stateMachine to newStateMachine, playing through the match
     * history to the current state so that currentState is expressed using
     * a MachineState generated by the new state machine.
     * 
     * This is not done in a thread-safe fashion with respect to the rest of
     * the gamer, so be careful when using this method.
     * 
     * @param newStateMachine the new state machine
     */
    protected final void switchStateMachine(StateMachine newStateMachine) {
        try {        
            MachineState newCurrentState = newStateMachine.getInitialState();
            Role newRole = newStateMachine.getRoleFromProp(getRoleName());

            // Attempt to run through the game history in the new machine
            List<List<GdlSentence>> theMoveHistory = getMatch().getMoveHistory();
            for(List<GdlSentence> nextMove : theMoveHistory) {
                List<Move> theJointMove = new ArrayList<Move>();
                for(GdlSentence theSentence : nextMove)
                    theJointMove.add(newStateMachine.getMoveFromSentence(theSentence));                    
                newCurrentState = newStateMachine.getNextStateDestructively(newCurrentState, theJointMove);
            }
            
            // Finally, switch over if everything went well.
            role = newRole;
            currentState = newCurrentState;            
            stateMachine = newStateMachine;
        } catch (Exception e) {
            GamerLogger.log("GamePlayer", "Caught an exception while switching state machine!");
            GamerLogger.logStackTrace("GamePlayer", e);
        }
    }	

    // =====================================================================
    // Finally, methods which are overridden with proper state-machine-based
	// semantics. These basically wrap a state-machine-based view of the world
	// around the ordinary metaGame() and selectMove() functions, calling the
	// new stateMachineMetaGame() and stateMachineSelectMove() functions after
	// doing the state-machine-related book-keeping.
	
	/**
	 * A wrapper function for stateMachineMetaGame. When the match begins, this
	 * initializes the state machine and role using the match description, and
	 * then calls stateMachineMetaGame.
	 */	
	@Override
	public final void metaGame(long timeout) throws MetaGamingException
	{
		try
		{
			stateMachine = getInitialStateMachine();
			stateMachine.initialize(getMatch().getGame().getRules());
			currentState = stateMachine.getInitialState();
			role = stateMachine.getRoleFromProp(getRoleName());
			getMatch().appendState(currentState.getContents());

			stateMachineMetaGame(timeout);
		}
		catch (Exception e)
		{
		    GamerLogger.logStackTrace("GamePlayer", e);
			throw new MetaGamingException();
		}
	}
	
	/**
	 * A wrapper function for stateMachineSelectMove. When we are asked to
	 * select a move, this advances the state machine up to the current state
	 * and then calls stateMachineSelectMove to select a move based on that
	 * current state.
	 */
	@Override
	public final GdlSentence selectMove(long timeout) throws MoveSelectionException
	{
		try
		{
			stateMachine.doPerMoveWork();

			List<GdlSentence> lastMoves = getMatch().getMostRecentMoves();
			if (lastMoves != null)
			{
				List<Move> moves = new ArrayList<Move>();
				for (GdlSentence sentence : lastMoves)
				{
					moves.add(stateMachine.getMoveFromSentence(sentence));
				}

				currentState = stateMachine.getNextState(currentState, moves);
				getMatch().appendState(currentState.getContents());
			}

			return stateMachineSelectMove(timeout).getContents();
		}
		catch (Exception e)
		{
		    GamerLogger.logStackTrace("GamePlayer", e);
			throw new MoveSelectionException();
		}
	}
    
    // Internal state about the current state of the state machine.
    private Role role;
    private MachineState currentState;
    private StateMachine stateMachine;          
}